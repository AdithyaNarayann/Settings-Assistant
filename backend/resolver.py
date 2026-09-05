"""Intent resolution: matches user transcript to settings graph nodes."""

import json
import os
import re
import logging
from typing import Optional
from models import (
    SettingsGraph, SettingsNode, ResolveResponse,
    NavigationPath, NavigationStep, NodeSelector
)
from prompts import (
    SYSTEM_PROMPT, RESOLVE_PROMPT_TEMPLATE,
    CLARIFICATION_FOLLOWUP_TEMPLATE, format_candidates
)

logger = logging.getLogger(__name__)

# Set to True to use LLM, False for keyword-only stub
USE_LLM = bool(os.environ.get("GEMINI_API_KEY"))
CONFIDENCE_THRESHOLD = 0.7


MANGLISH_SYNONYMS = {
    "valuthakkanam": ["font", "size", "display", "large", "text"],
    "valuthaakkanam": ["font", "size", "display", "large", "text"],
    "valuthu": ["font", "size", "display", "large", "text"],
    "kurakkanam": ["volume", "sound", "audio", "ringtone"],
    "kurakkaanam": ["volume", "sound", "audio", "ringtone"],
    "shabdam": ["sound", "volume", "audio", "ringtone"],
    "letters": ["font", "text", "display", "size"],
    "aksharam": ["font", "text", "display", "size"],
    "velicham": ["brightness", "display"],
    "light": ["brightness", "display"],
    "net": ["network", "wifi", "internet", "data"],
    "bhasha": ["language", "input", "keyboard"],
    "battery": ["battery", "power"],
    "sound": ["sound", "volume", "audio"],
}


def find_candidates(graph: SettingsGraph, transcript: str, max_candidates: int = 30) -> list[SettingsNode]:
    """Find candidate nodes that might match the transcript.
    
    Simple substring/keyword matching — no vector DB needed for ~800 nodes.
    """
    transcript_lower = transcript.lower()
    raw_tokens = re.findall(r'\w+', transcript_lower)
    tokens = set(raw_tokens)
    for t in raw_tokens:
        if t in MANGLISH_SYNONYMS:
            tokens.update(MANGLISH_SYNONYMS[t])
    
    scored: list[tuple[SettingsNode, int]] = []
    
    for node in graph.nodes:
        score = 0
        label_lower = node.label.lower()
        subtitle_lower = (node.subtitle or "").lower()
        
        # Exact label match = high score
        if label_lower in transcript_lower:
            score += 10
        
        # Exact transcript in label
        if transcript_lower in label_lower:
            score += 8
        
        # Token matches in label
        for token in tokens:
            if len(token) >= 3 and token in label_lower:
                score += 4
            if len(token) >= 3 and token in subtitle_lower:
                score += 2
        
        if score > 0:
            scored.append((node, score))
    
    # Sort by score descending, take top candidates
    scored.sort(key=lambda x: x[1], reverse=True)
    return [node for node, _ in scored[:max_candidates]]


def build_path(graph: SettingsGraph, target_node: SettingsNode) -> NavigationPath:
    """Build a navigation path from root to the target node."""
    # Walk up from target to root, collecting ancestors
    path_nodes: list[SettingsNode] = []
    current = target_node
    visited_ids: set[str] = set()
    
    while current:
        if current.id in visited_ids:
            break  # Avoid infinite loops
        visited_ids.add(current.id)
        path_nodes.append(current)
        if current.parent_id:
            parent = next((n for n in graph.nodes if n.id == current.parent_id), None)
            current = parent
        else:
            break
    
    path_nodes.reverse()  # Root to target
    
    # Determine if we can use a direct intent for the first hop
    direct_intent = None
    start_idx = 0
    for i, node in enumerate(path_nodes):
        if node.direct_intent_action:
            direct_intent = node.direct_intent_action
            start_idx = i + 1  # Skip nodes up to and including the direct-intent node
            break
    
    # Build steps from start_idx to end
    steps = []
    for node in path_nodes[start_idx:]:
        steps.append(NavigationStep(
            screen_signature_hint=node.screen_signature,
            click_target=NodeSelector(
                resource_id=node.selector.resource_id,
                text=node.selector.text,
                content_description=node.selector.content_description
            ),
            label=node.label
        ))
    
    return NavigationPath(
        direct_intent_action=direct_intent,
        steps=steps
    )


def resolve_stub(graph: SettingsGraph, transcript: str) -> ResolveResponse:
    """Stub resolver using simple keyword matching (no LLM)."""
    candidates = find_candidates(graph, transcript)
    
    if not candidates:
        return ResolveResponse(
            type="clarification",
            question="I couldn't find a matching setting. Could you describe it differently?",
            confidence=0.0
        )
    
    top = candidates[0]
    # Simple confidence: top score relative to second-best
    confidence = min(0.9, 0.5 + (0.1 * len(candidates)))
    
    if confidence >= CONFIDENCE_THRESHOLD:
        path = build_path(graph, top)
        return ResolveResponse(
            type="resolved",
            path=path,
            confidence=confidence
        )
    else:
        options = [n.label for n in candidates[:3]]
        question = f"Did you mean: {', '.join(options)}?"
        return ResolveResponse(
            type="clarification",
            question=question,
            confidence=confidence
        )


async def resolve_with_llm(
    graph: SettingsGraph,
    transcript: str,
    conversation_state: Optional[str] = None
) -> ResolveResponse:
    """Resolve using Google Gemini API.
    
    Sends the transcript + candidate nodes to Gemini, asks it to return
    structured JSON with either a resolved node ID + confidence, or a
    clarifying question.
    """
    import google.generativeai as genai
    
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        logger.warning("GEMINI_API_KEY not set, falling back to stub resolver")
        return resolve_stub(graph, transcript)
    
    genai.configure(api_key=api_key)
    
    # Find candidate nodes via keyword matching (pre-filter for the LLM)
    candidates = find_candidates(graph, transcript, max_candidates=30)
    
    if not candidates:
        # Even the keyword search found nothing — LLM won't help
        return ResolveResponse(
            type="clarification",
            question="I couldn't find any matching settings. Could you describe what you want differently?",
            confidence=0.0
        )
    
    # Build the prompt
    candidates_text = format_candidates(candidates)
    
    if conversation_state:
        # This is a follow-up after a clarification
        try:
            state = json.loads(conversation_state)
            previous_question = state.get("question", "")
        except (json.JSONDecodeError, TypeError):
            previous_question = ""
        
        user_prompt = CLARIFICATION_FOLLOWUP_TEMPLATE.format(
            previous_question=previous_question,
            transcript=transcript,
            candidates=candidates_text
        )
    else:
        user_prompt = RESOLVE_PROMPT_TEMPLATE.format(
            transcript=transcript,
            candidates=candidates_text
        )
    
    try:
        model = genai.GenerativeModel("gemini-1.5-flash")
        response = model.generate_content(
            [
                {"role": "user", "parts": [SYSTEM_PROMPT]},
                {"role": "model", "parts": ["Understood. I will analyze the user's query and match it to the most relevant setting, returning structured JSON as specified."]},
                {"role": "user", "parts": [user_prompt]}
            ],
            generation_config=genai.GenerationConfig(
                temperature=0.1,
                max_output_tokens=1024,
                response_mime_type="application/json"
            )
        )
        
        # Parse the LLM response
        response_text = response.text.strip()
        logger.info(f"LLM response: {response_text}")
        
        # Extract JSON from response (handle markdown code blocks)
        json_match = re.search(r'```json\s*(.*?)\s*```', response_text, re.DOTALL)
        if json_match:
            response_text = json_match.group(1)
        
        llm_result = json.loads(response_text)
        
        if llm_result.get("type") == "resolved":
            target_id = llm_result.get("target_node_id")
            confidence = float(llm_result.get("confidence", 0.5))
            
            # Find the target node
            target_node = next(
                (n for n in graph.nodes if n.id == target_id),
                None
            )
            
            if target_node is None:
                # LLM returned an invalid node ID — fall back to top keyword match
                logger.warning(f"LLM returned invalid node ID: {target_id}")
                target_node = candidates[0]
                confidence = max(0.5, confidence - 0.2)
            
            if confidence >= CONFIDENCE_THRESHOLD:
                path = build_path(graph, target_node)
                return ResolveResponse(
                    type="resolved",
                    path=path,
                    confidence=confidence
                )
            else:
                # Below threshold — ask for clarification
                options = [n.label for n in candidates[:3]]
                question = f"I'm not sure. Did you mean: {', '.join(options)}?"
                return ResolveResponse(
                    type="clarification",
                    question=question,
                    confidence=confidence,
                    conversation_state=json.dumps({
                        "question": question,
                        "candidate_ids": [n.id for n in candidates[:5]]
                    })
                )
        
        elif llm_result.get("type") == "clarification":
            question = llm_result.get("question", "Could you be more specific?")
            candidate_ids = llm_result.get("candidate_node_ids", [])
            
            return ResolveResponse(
                type="clarification",
                question=question,
                confidence=0.0,
                conversation_state=json.dumps({
                    "question": question,
                    "candidate_ids": candidate_ids
                })
            )
        
        else:
            logger.warning(f"Unexpected LLM response type: {llm_result}")
            return resolve_stub(graph, transcript)
    
    except json.JSONDecodeError as e:
        logger.error(f"Failed to parse LLM JSON response: {e}")
        return resolve_stub(graph, transcript)
    except Exception as e:
        logger.error(f"LLM call failed: {e}")
        return resolve_stub(graph, transcript)


async def resolve(
    graph: SettingsGraph,
    transcript: str,
    conversation_state: Optional[str] = None
) -> ResolveResponse:
    """Main resolve entry point."""
    if USE_LLM:
        return await resolve_with_llm(graph, transcript, conversation_state)
    else:
        return resolve_stub(graph, transcript)

