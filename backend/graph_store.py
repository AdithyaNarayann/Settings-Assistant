import json
import uuid
import os
from typing import Optional
from models import SettingsGraph

STORE_DIR = "graph_data"


class GraphStore:
    """Simple in-memory + file-based graph storage."""
    
    def __init__(self):
        self._cache: dict[str, SettingsGraph] = {}
        os.makedirs(STORE_DIR, exist_ok=True)
        self._load_all()
    
    def _load_all(self):
        """Load all stored graphs from disk into cache."""
        for filename in os.listdir(STORE_DIR):
            if filename.endswith(".json"):
                try:
                    with open(os.path.join(STORE_DIR, filename), "r") as f:
                        data = json.load(f)
                        graph = SettingsGraph(**data)
                        if graph.graph_id:
                            self._cache[graph.graph_id] = graph
                except Exception as e:
                    print(f"Failed to load {filename}: {e}")
    
    def store_graph(self, graph: SettingsGraph) -> str:
        """Store a graph and return its ID."""
        graph_id = str(uuid.uuid4())[:8]
        graph.graph_id = graph_id
        
        # Save to disk
        filepath = os.path.join(STORE_DIR, f"{graph_id}.json")
        with open(filepath, "w") as f:
            f.write(graph.model_dump_json(indent=2, by_alias=True))
        
        # Cache in memory
        self._cache[graph_id] = graph
        
        return graph_id
    
    def get_graph(self, graph_id: str) -> Optional[SettingsGraph]:
        """Retrieve a graph by ID."""
        return self._cache.get(graph_id)
    
    def list_graphs(self) -> list[str]:
        """List all stored graph IDs."""
        return list(self._cache.keys())


# Singleton instance
store = GraphStore()
