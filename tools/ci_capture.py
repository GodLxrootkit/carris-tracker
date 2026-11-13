#!/usr/bin/env python3
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "pt.carrismetropolitana.tracker"


def adb(*args, **kwargs):
    return subprocess.run(["adb", *args], check=True, **kwargs)


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", stdout=subprocess.DEVNULL)
    return ET.fromstring(subprocess.check_output(["adb", "exec-out", "cat", "/sdcard/ui.xml"]))


def find(text=None, desc=None):
    for node in dump().iter("node"):
        if (text and node.get("text") == text) or (desc and node.get("content-desc") == desc):
            match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
            if match:
                x = (int(match.group(1)) + int(match.group(3))) // 2
                y = (int(match.group(2)) + int(match.group(4))) // 2
                return x, y
    raise LookupError(text or desc)


def wait_for(text=None, desc=None, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            return find(text, desc)
        except (LookupError, subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1)
    raise LookupError(f"not found after {timeout}s: {text or desc}")


def tap(text=None, desc=None, wait=1.0):
    x, y = find(text, desc)
    adb("shell", "input", "tap", str(x), str(y), stdout=subprocess.DEVNULL)
    time.sleep(wait)


def shot(name):
    Path("screenshots").mkdir(exist_ok=True)
    with open(f"screenshots/{name}", "wb") as f:
        adb("exec-out", "screencap", "-p", stdout=f)


def main():
    wait_for("Paragens", timeout=90)
    time.sleep(15)
    shot("paragens.png")

    tap("Metro", wait=5)
    shot("paragens-metro.png")

    tap("Carris", wait=1)
    tap("Definições", wait=6)
    shot("definicoes.png")

    tap("Paragens", wait=1)
    tap("Mapa", wait=15)
    shot("mapa.png")



if __name__ == "__main__":
    main()
