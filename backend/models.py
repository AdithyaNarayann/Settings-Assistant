from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel
from typing import Optional
from datetime import datetime
import uuid


class BaseSchema(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
    )


class NodeSelector(BaseSchema):
    """Selector for re-finding a UI node in a live accessibility tree."""
    resource_id: Optional[str] = None
    text: Optional[str] = None
    content_description: Optional[str] = None


class SettingsNode(BaseSchema):
    """A single node in the crawled Settings graph."""
    id: str
    label: str
    subtitle: Optional[str] = None
    screen_signature: str
    parent_id: Optional[str] = None
    selector: NodeSelector
    depth: int
    direct_intent_action: Optional[str] = None
    class_name: Optional[str] = None
    is_clickable: bool = True
    child_ids: list[str] = []


class SettingsGraph(BaseSchema):
    """The complete crawled settings graph for a device."""
    graph_id: Optional[str] = None
    device_manufacturer: str
    device_model: str
    android_version: int
    nodes: list[SettingsNode]
    created_at: str
    screen_signatures: list[str] = []


class NavigationStep(BaseSchema):
    """A single step in a navigation path."""
    screen_signature_hint: Optional[str] = None
    click_target: NodeSelector
    label: Optional[str] = None


class NavigationPath(BaseSchema):
    """Ordered sequence of navigation steps to reach a target setting."""
    direct_intent_action: Optional[str] = None
    steps: list[NavigationStep]


class ResolveRequest(BaseSchema):
    """Request to resolve a user's voice query against a stored graph."""
    graph_id: str
    transcript: str
    conversation_state: Optional[str] = None


class ResolveResponse(BaseSchema):
    """Response from the resolve endpoint."""
    type: str  # "resolved" or "clarification"
    path: Optional[NavigationPath] = None
    confidence: Optional[float] = None
    question: Optional[str] = None
    conversation_state: Optional[str] = None


class GraphUploadResponse(BaseSchema):
    """Response after uploading a graph."""
    graph_id: str
    node_count: int
    message: str
