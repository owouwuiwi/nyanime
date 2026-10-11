#!/usr/bin/env bash
# The build uses the original Android libxml2 binary and matching generated headers.
set -euo pipefail
root=$(cygpath -u "$ANIYOMI_NATIVE_ROOT")
if [[ " $* " != *' --version '* && " $* " != *' libxml-2.0 '* ]]; then exit 1; fi
case " $* " in
    *' --version '*) printf '%s\n' '1.0.0' ;;
    *' --exists '*) exit 0 ;;
    *' --cflags '*) printf '%s\n' "-I$root/libxml2/include -I$root/build-$ANIYOMI_NATIVE_ABI/xml" ;;
    *' --libs '*) printf '%s\n' "-L$root/prefix-$ANIYOMI_NATIVE_ABI/lib -lxml2" ;;
    *' --modversion '*) printf '%s\n' '2.13.5' ;;
    *) exit 1 ;;
esac
