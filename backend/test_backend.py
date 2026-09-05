import pytest
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

SAMPLE_GRAPH = {
    "deviceManufacturer": "Google",
    "deviceModel": "Pixel 8",
    "androidVersion": 34,
    "createdAt": "2026-09-05T00:00:00Z",
    "screenSignatures": ["sig_root", "sig_display", "sig_sound"],
    "nodes": [
        {
            "id": "node_root_display",
            "label": "Display",
            "subtitle": "Brightness, display size, dark theme",
            "screenSignature": "sig_root",
            "parentId": None,
            "selector": {
                "resourceId": "android:id/title",
                "text": "Display",
                "contentDescription": None
            },
            "depth": 0,
            "directIntentAction": "android.settings.DISPLAY_SETTINGS",
            "className": "android.widget.TextView",
            "isClickable": True,
            "childIds": ["node_font_size", "node_dark_theme"]
        },
        {
            "id": "node_root_sound",
            "label": "Sound & vibration",
            "subtitle": "Volume, haptics, Do Not Disturb",
            "screenSignature": "sig_root",
            "parentId": None,
            "selector": {
                "resourceId": "android:id/title",
                "text": "Sound & vibration",
                "contentDescription": None
            },
            "depth": 0,
            "directIntentAction": "android.settings.SOUND_SETTINGS",
            "className": "android.widget.TextView",
            "isClickable": True,
            "childIds": ["node_media_volume", "node_ring_volume"]
        },
        {
            "id": "node_font_size",
            "label": "Font size",
            "subtitle": "Make text larger or smaller",
            "screenSignature": "sig_display",
            "parentId": "node_root_display",
            "selector": {
                "resourceId": "com.android.settings:id/font_size",
                "text": "Font size",
                "contentDescription": None
            },
            "depth": 1,
            "directIntentAction": None,
            "className": "android.widget.TextView",
            "isClickable": True,
            "childIds": []
        },
        {
            "id": "node_media_volume",
            "label": "Media volume",
            "subtitle": None,
            "screenSignature": "sig_sound",
            "parentId": "node_root_sound",
            "selector": {
                "resourceId": "com.android.settings:id/media_volume",
                "text": "Media volume",
                "contentDescription": None
            },
            "depth": 1,
            "directIntentAction": None,
            "className": "android.widget.SeekBar",
            "isClickable": True,
            "childIds": []
        }
    ]
}


def test_health():
    res = client.get("/health")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "ok"


def test_upload_graph():
    res = client.post("/graph", json=SAMPLE_GRAPH)
    assert res.status_code == 200
    data = res.json()
    assert "graphId" in data or "graph_id" in data
    graph_id = data.get("graphId") or data.get("graph_id")
    assert graph_id is not None
    assert (data.get("nodeCount") or data.get("node_count")) == 4
    return graph_id


def test_resolve_resolved():
    # First upload
    upload_res = client.post("/graph", json=SAMPLE_GRAPH)
    graph_id = upload_res.json().get("graphId") or upload_res.json().get("graph_id")

    # Resolve "font size" query
    resolve_res = client.post("/resolve", json={
        "graphId": graph_id,
        "transcript": "font size"
    })
    assert resolve_res.status_code == 200
    data = resolve_res.json()
    assert data["type"] == "resolved"
    assert data["path"] is not None
    assert data["confidence"] is not None
    # Check that directIntentAction is used or steps navigate to font size
    steps = data["path"]["steps"]
    assert len(steps) >= 1
    assert any("font size" in (s.get("label") or "").lower() for s in steps)


def test_resolve_manglish():
    upload_res = client.post("/graph", json=SAMPLE_GRAPH)
    graph_id = upload_res.json().get("graphId") or upload_res.json().get("graph_id")

    # Resolve "phone-il letters valuthakkanam" (Manglish query for font size)
    resolve_res = client.post("/resolve", json={
        "graphId": graph_id,
        "transcript": "phone-il letters valuthakkanam"
    })
    assert resolve_res.status_code == 200
    data = resolve_res.json()
    assert data["type"] == "resolved"
    assert data["path"] is not None
    steps = data["path"]["steps"]
    assert any("font size" in (s.get("label") or "").lower() for s in steps)


def test_resolve_ambiguous_or_unknown():
    upload_res = client.post("/graph", json=SAMPLE_GRAPH)
    graph_id = upload_res.json().get("graphId") or upload_res.json().get("graph_id")

    # Test unknown setting query
    resolve_res = client.post("/resolve", json={
        "graphId": graph_id,
        "transcript": "something completely random and non-existent xyz123"
    })
    assert resolve_res.status_code == 200
    data = resolve_res.json()
    assert data["type"] == "clarification"
    assert data["question"] is not None


if __name__ == "__main__":
    print("Testing health...")
    test_health()
    print("Testing upload...")
    gid = test_upload_graph()
    print("Testing resolve (English)...")
    test_resolve_resolved()
    print("Testing resolve (Manglish)...")
    test_resolve_manglish()
    print("Testing clarification...")
    test_resolve_ambiguous_or_unknown()
    print("All backend tests PASSED!")

