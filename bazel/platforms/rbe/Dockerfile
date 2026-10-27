# BuildBuddy's Ubuntu 24.04 RBE image plus what Skiko loads, so the Compose Desktop UI tests run on the executors
# too. Published to ghcr.io by .github/workflows/rbe-image.yml; //bazel/platforms:rbe_linux_x64 pins its digest.
FROM gcr.io/flame-public/rbe-ubuntu24-04@sha256:5f0c01e5f6d79e58d7a996330de33aa38ce599bf8431eaf27521e7602daadf50

# libskiko-linux-x64.so links libGL, libX11 and fontconfig; fontconfig needs a font to lay out text. lld: the C
# toolchain is configured on the machine running Bazel, and one with lld links with `-fuse-ld=lld` (the Go binaries).
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libx11-6 libfontconfig1 fonts-dejavu-core lld \
    && rm -rf /var/lib/apt/lists/*
