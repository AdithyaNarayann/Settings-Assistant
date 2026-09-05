"""Optional cloud STT proxy endpoint.

Keeps the seam clean so cloud STT can be added without touching
the resolve endpoint.
"""

from fastapi import UploadFile
from typing import Optional


async def transcribe_audio(audio_file: UploadFile, language: str = "ml-IN") -> Optional[str]:
    """Proxy audio to a cloud STT provider.
    
    Stub implementation — returns None to indicate cloud STT is not configured.
    When implemented, this would call Google Cloud Speech-to-Text, Whisper API, etc.
    """
    # TODO: Implement cloud STT when on-device recognition proves insufficient
    return None
