import hashlib
import sys
from pathlib import Path
import h5py
import numpy as np
from scipy.signal import resample_poly
from scipy.io.wavfile import write

root = Path(__file__).parent
source = Path(sys.argv[1])
assert hashlib.sha256(source.read_bytes()).hexdigest() == "2768ac841213a7ae11d1ea7fd0f25a69b39216102dc5dd913ea6ba0f0dc57e28"
output = Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
layouts = {
    "surround51": ["FL", "FR", "FC", "LFE", "BL", "BR"],
    "surround51side": ["FL", "FR", "FC", "LFE", "SL", "SR"],
    "surround71": ["FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR"],
}
angles = dict(FL=30, FR=330, FC=0, BL=150, BR=210, SL=90, SR=270)
with h5py.File(source) as sofa:
    positions = sofa["SourcePosition"][:]
    assert float(sofa["Data.SamplingRate"][0]) == 44100
    assert np.all(sofa["Data.Delay"][:] == 0)
    impulses = {}
    for channel, angle in angles.items():
        index = np.flatnonzero((positions[:, 0] == angle) & (positions[:, 1] == 0))
        assert len(index) == 1
        impulses[channel] = resample_poly(sofa["Data.IR"][index[0]].T, 160, 147, axis=0)
    impulses["LFE"] = np.zeros_like(impulses["FC"])
    for layout, channels in layouts.items():
        samples = np.concatenate([impulses[ch] for ch in channels], axis=1)
        write(output / (layout + ".wav"), 48000, samples.astype("<f4"))
        print(layout, samples.shape, hashlib.sha256((output / (layout + ".wav")).read_bytes()).hexdigest())
