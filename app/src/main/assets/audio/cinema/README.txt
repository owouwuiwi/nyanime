MIT KEMAR headphone impulse responses
Bill Gardner and Keith Martin, MIT Media Laboratory, Copyright 1994.
https://sound.media.mit.edu/resources/KEMAR.html
Redistribution and use permitted with attribution; see LICENSE.txt.

Derived from MIT_KEMAR_normal_pinna.sofa distributed by libmysofa at commit
64bc8c61ca1c2acab2eb9818ebd6f5594d1317b7.
Input SHA-256: 2768ac841213a7ae11d1ea7fd0f25a69b39216102dc5dd913ea6ba0f0dc57e28
The SOFA file's generic missing-license attribute is superseded by the accompanying
tests/LICENSE.MIT_KEMAR_pinnae and the original MIT data archive notice.

All positions have zero elevation. SOFA azimuths are positive towards the left:
FL 30, FR 330, FC 0, BL 150, BR 210, SL 90, SR 270 degrees.
Pairs are left ear then right ear, in the channel order in HeadphoneCinema.kt.
LFE pairs contain zeros because FFmpeg headphone mixes LFE directly to both ears.
44,100 Hz measurements resampled to 48,000 Hz, 558 taps, float32 WAV.
No per-ear normalization: interaural levels and arrival times are preserved.
Generate using tools/audio/prepare_headphone_hrir.py; no model download is needed.
