# Third-party notices

OpenNest remains licensed under AGPL-3.0-only. The following notice applies to the historical 94-byte `small_1x1.webp` fixture previously embedded in `ImageValidatorTests`, copied from TwelveMonkeys tag `twelvemonkeys-3.15.0`. Dependency JARs retain their own packaged notices.

AVIF validation pins [JavaCV 1.5.14](https://github.com/bytedeco/javacv/tree/1.5.14) and [JavaCPP presets FFmpeg 8.1.2-1.5.14](https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg). JavaCV/JavaCPP use their Apache-2.0 option; the standard non-GPL FFmpeg runtime reports LGPL-3.0-or-later. The backend packages their upstream license texts and source/build references under `META-INF/licenses/media`. Native JARs do not supply a complete component notice bundle: binary releases must retain the linked libraries' notices and matching sources/build materials identified by the [pinned build script](https://github.com/bytedeco/javacpp-presets/blob/1.5.14/ffmpeg/cppbuild.sh). No binary release or deployment is performed here, and OpenNest stays AGPL-3.0-only. Browser wrapper/codec notices are documented in [frontend/THIRD_PARTY_NOTICES.md](../frontend/THIRD_PARTY_NOTICES.md) and included with its WASM. The AVIF fixture is generated from fabricated pixels by `frontend/scripts/avif-fixtures.mjs`.

Source: https://github.com/haraldk/TwelveMonkeys/blob/twelvemonkeys-3.15.0/imageio/imageio-webp/src/test/resources/webp/small_1x1.webp

```text
BSD 3-Clause License

Copyright (c) 2008-2020, Harald Kuhr
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
