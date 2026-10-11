"""Build the pinned official TDLib JSON/JNI engine; never fetch a moving branch."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

TDLIB = "42e6a5259551178d1dab54a22ad96d14bd906e20"
OPENSSL = "d0ca66a1abe52545f14eca635c648932fcde5615"
NDK = "27.1.12297006"
ABIS = {"arm64-v8a": "android-arm64", "armeabi-v7a": "android-arm", "x86": "android-x86", "x86_64": "android-x86_64"}


def run(*args, cwd=None, env=None):
    subprocess.run(args, cwd=cwd, env=env, check=True)


def checkout(url, revision, destination):
    if not (destination / ".git").exists():
        destination.mkdir(parents=True, exist_ok=True)
        run("git", "init", str(destination))
        run("git", "remote", "add", "origin", url, cwd=destination)
    run("git", "fetch", "--depth=1", "origin", revision, cwd=destination)
    run("git", "checkout", "--detach", "FETCH_HEAD", cwd=destination)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", choices=ABIS, required=True)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"), required=not os.environ.get("ANDROID_HOME"))
    parser.add_argument("--jobs", default=2, type=int)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    work = root / ".local" / "telegram-build" / args.abi
    td = work / "td"
    ssl = work / "openssl"
    checkout("https://github.com/tdlib/td.git", TDLIB, td)
    checkout("https://github.com/openssl/openssl.git", OPENSSL, ssl)
    ndk = Path(args.sdk).resolve() / "ndk" / NDK
    env = dict(os.environ)
    env["ANDROID_NDK_ROOT"] = str(ndk)
    env["PATH"] = str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin") + os.pathsep + env["PATH"]
    install = work / "openssl-install"
    run("perl", "Configure", ABIS[args.abi], "no-shared", "no-tests", "-D__ANDROID_API__=26",
        "--prefix=" + str(install), "--libdir=lib", cwd=ssl, env=env)
    run("make", "-j" + str(args.jobs), cwd=ssl, env=env)
    run("make", "install_sw", cwd=ssl, env=env)
    generated = work / "generated"
    run("cmake", "-S", str(td / "example/android"), "-B", str(generated), "-GNinja",
        "-DTD_ANDROID_JSON_JAVA=ON", "-DTD_GENERATE_SOURCE_FILES=ON", "-DCMAKE_BUILD_TYPE=Release")
    run("cmake", "--build", str(generated), "--parallel", str(args.jobs))
    build = work / "android"
    run("cmake", "-S", str(td / "example/android"), "-B", str(build), "-GNinja",
        "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
        "-DANDROID_ABI=" + args.abi, "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DOPENSSL_ROOT_DIR=" + str(install),
        "-DOPENSSL_USE_STATIC_LIBS=TRUE", "-DTD_ANDROID_JSON_JAVA=ON", "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
    run("cmake", "--build", str(build), "--target", "tdjni", "--parallel", str(args.jobs))
    output = root / ".local/telegram-native/jniLibs" / args.abi
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(build / "libtdjsonjava.so", output / "libtdjsonjava.so")
    (output / "provenance.json").write_text(json.dumps({"tdlib": TDLIB, "openssl": OPENSSL, "ndk": NDK, "abi": args.abi}))


if __name__ == "__main__":
    main()
