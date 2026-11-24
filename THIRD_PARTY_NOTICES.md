# Third-Party Notices

This repository combines original project code with third-party source code,
binary components, fonts, and artwork. The root `GPL-3.0` license applies only
to material for which the project authors have the right to grant that license.
Third-party material remains under its own license or copyright terms.

## Upstream Project Attribution (BBBEANNN/limbusZhCN)

- Upstream inspiration & initial fork base: <https://github.com/BBBEANNN/limbusZhCN>
- License: GNU General Public License v3.0 (`GPL-3.0`)

The initial engineering scaffold of this repository was derived from `BBBEANNN/limbusZhCN` under the terms of `GPL-3.0`. Subsequent development in this repository has independently rewritten the user interface, added Android 17 (API 37) compatibility, expanded the IL2CPP/TMP dynamic rendering and dual-weight font pipeline, and introduced multi-pack drop-in management and reverse-landscape orientation locking.

## VirtualApp

- Location: `third_party/virtualapp-upstream/`
- Source: <https://github.com/ServenScorpion/VirtualApp>
- Pinned revision: `ae7c4275096b6614fa1aa7befe326630fd3f8833`
- Import record: `third_party/virtualapp-upstream/UPSTREAM.txt`

The pinned upstream revision does not contain a repository-level license file.
Some individual files contain Apache-2.0, MIT, Android Open Source Project, or
other copyright/license headers, which remain in force. The root GPL license
does not purport to relicense files for which the repository owner does not own
the copyright. Downstream users are responsible for independently determining
whether their use and redistribution of this component are permitted.

## microG Services and Companion / FakeStore

- Source: <https://github.com/microg/GmsCore>
- License: Apache License 2.0
- Component versions and hashes: `app/src/main/assets/microg/NOTICE.txt`
- License text: `app/src/main/assets/licenses/microG-Apache-2.0.txt`

The APK files are bundled so a fresh container can bootstrap the Google-service
compatibility layer without downloading executable code at first launch.

## Chinese localization fonts

- Runtime asset (Regular): `app/src/main/assets/runtime/ChineseFont.ttf` (`SarasaGothicSC-Regular.ttf`, SHA-256: `6541a94ad09601b71dff4100360807f4bb2068a0d5fe8b76c71a75e0cb6cf749`)
- Runtime asset (Bold): `app/src/main/assets/runtime/ChineseFontBold.ttf` (`SarasaGothicSC-Bold.ttf`, SHA-256: `a56a06f1af27726bc5def015b61deecbbdf5ae6d954a91b1131d8a17290def35`)
- Source project: <https://github.com/be5invis/Sarasa-Gothic>
- Release asset: `SarasaGothicSC-TTF-1.0.40.7z`
- Sarasa Gothic license: SIL Open Font License 1.1
- OFL text: `app/src/main/assets/licenses/Sarasa-Gothic-OFL-1.1.txt`

The fonts remain subject to the SIL Open Font License and are not relicensed under
GPL-3.0.

## Native hook and Android-derived files

The vendored runtime contains Android Open Source Project-derived files and
native hook implementations whose Apache-2.0, MIT, or other notices are kept in
their original source headers. Those headers control the corresponding files.

## Game names and artwork

Limbus Company, Project Moon, related names, characters, and artwork belong to
their respective rights holders. This is an unofficial community project and is
not endorsed by or affiliated with Project Moon. No ownership of third-party
trademarks or artwork is claimed.
