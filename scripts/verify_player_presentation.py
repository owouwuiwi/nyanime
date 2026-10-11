"""Check the renderer AAR boundary and packaged notices; not a license certificate."""

import argparse
import io
from pathlib import Path
import struct
import zipfile


FORBIDDEN = ("io/github/aedev/flow", "io.github.aedev.flow", "nyanime/video", "nyanime.video", "org/schabi/newpipe", "org.schabi.newpipe", "com/metrolist", "com.metrolist")
REQUIRED = {
    "it/lorelore/nyanime/presentation/player/PlayerChromeKt.class",
    "it/lorelore/nyanime/presentation/player/flow/ui/components/videoplayer/controls/PlayerControlsOverlayKt.class",
    "it/lorelore/nyanime/presentation/player/VideoChromeState.class",
}
NOTICES = (
    ("PlayerPresentation-LICENSE.txt", "LICENSE"),
    ("PlayerPresentation-NOTICE.txt", "NOTICE"),
    ("PlayerPresentation-Apache-2.0.txt", "third-party/Apache-2.0.txt"),
    ("FlowPlayerStyle-LICENSE.txt", "third-party/flow-ui/LICENSE"),
    ("FlowPlayerStyle-NOTICE.txt", "third-party/flow-ui/NOTICE"),
)


def class_strings(raw):
    if raw[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid Java class")
    count = struct.unpack_from(">H", raw, 8)[0]
    offset, index, strings = 10, 1, []
    while index < count:
        tag = raw[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", raw, offset)[0]
            offset += 2
            strings.append(raw[offset:offset + length].decode("utf-8", errors="replace"))
            offset += length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            offset += 2
        elif tag == 15:
            offset += 3
        else:
            raise ValueError("Unknown Java constant tag: " + str(tag))
        if offset > len(raw):
            raise ValueError("Truncated Java constants")
        index += 1
    return strings


def verify(aar_path, module_dir):
    module_dir = Path(module_dir)
    with zipfile.ZipFile(aar_path) as aar:
        with zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as classes:
            names = set(classes.namelist())
            if REQUIRED - names:
                raise ValueError("Renderer classes missing: " + repr(sorted(REQUIRED - names)))
            for name in names:
                if not name.endswith(".class"):
                    continue
                for value in [name, *class_strings(classes.read(name))]:
                    if any(prefix in value for prefix in FORBIDDEN):
                        raise ValueError("Optional feature reference in renderer " + name + ": " + value)
        for asset, source in NOTICES:
            if aar.read("assets/licenses/" + asset) != (module_dir / source).read_bytes():
                raise ValueError("Renderer notice differs from corresponding source: " + asset)
        if any(name.startswith("jni/") and name.endswith(".so") for name in aar.namelist()):
            raise ValueError("Renderer unexpectedly contains native engines")
    print("PLAYER_PRESENTATION_AAR_OK: " + str(aar_path))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar")
    parser.add_argument("--module", default="presentation-player")
    args = parser.parse_args()
    verify(args.aar, args.module)
