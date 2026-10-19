# BuildBuddy's Ubuntu 24.04 RBE image plus what Skiko loads, so the Compose Desktop UI tests run on the executors
# too. Published to ghcr.io by .github/workflows/rbe-image.yml; //bazel/platforms:rbe_linux_x64 pins its digest.
FROM gcr.io/flame-public/rbe-ubuntu24-04@sha256:5d2d8b3935c4dad4d81e0b6d89eeb06734d0e708cdf3d8ec779cf619c9243860

# libskiko-linux-x64.so links libGL, libX11 and fontconfig; fontconfig needs a font to lay out text. lld: the C
# toolchain is configured on the machine running Bazel, and one with lld links with `-fuse-ld=lld` (the Go binaries).
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libx11-6 libfontconfig1 fonts-dejavu-core lld \
    && rm -rf /var/lib/apt/lists/*
