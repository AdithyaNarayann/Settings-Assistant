"""Settings Lens Backend — FastAPI server.

Endpoints:
- POST /graph   — Upload a crawled settings graph
- POST /resolve — Resolve a voice transcript to a navigation path
- POST /stt     — (Optional) Proxy audio to cloud STT
- GET  /health  — Health check
"""

from fastapi import FastAPI, HTTPException, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
from models import SettingsGraph, ResolveRequest, ResolveResponse, GraphUploadResponse
from graph_store import store
from resolver import resolve
from stt_proxy import transcribe_audio

app = FastAPI(
    title="Settings Lens API",
    description="Backend for the Settings Lens Android app",
    version="0.1.0"
)

# CORS — allow all origins for development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health():
    return {"status": "ok", "graphs_stored": len(store.list_graphs())}


@app.post("/graph", response_model=GraphUploadResponse)
async def upload_graph(graph: SettingsGraph):
    """Accept a crawled graph from the Android app and store it."""
    graph_id = store.store_graph(graph)
    return GraphUploadResponse(
        graph_id=graph_id,
        node_count=len(graph.nodes),
        message=f"Graph stored successfully with {len(graph.nodes)} nodes"
    )


@app.post("/resolve", response_model=ResolveResponse)
async def resolve_intent(request: ResolveRequest):
    """Resolve a voice transcript against a stored graph.
    
    The app sends graph_id + transcript (NOT the full graph).
    The backend already has the graph from the initial upload.
    """
    graph = store.get_graph(request.graph_id)
    if graph is None:
        raise HTTPException(
            status_code=404,
            detail=f"Graph '{request.graph_id}' not found. Please upload the graph first."
        )
    
    result = await resolve(graph, request.transcript, request.conversation_state)
    return result


@app.post("/stt")
async def speech_to_text(audio: UploadFile = File(...), language: str = "ml-IN"):
    """Optional: Proxy audio to cloud STT provider.
    
    Returns the transcript if cloud STT is configured, otherwise 501.
    """
    transcript = await transcribe_audio(audio, language)
    if transcript is None:
        raise HTTPException(
            status_code=501,
            detail="Cloud STT not configured. Use on-device recognition."
        )
    return {"transcript": transcript}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
