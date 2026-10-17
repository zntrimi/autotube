# localMaven

NewPipeExtractor の未リリース修正版（`net.newpipe:extractor:v0.26.5-nsig`）。

YouTube が 2026-09-08 ごろ n パラメータの難読化を変えて v0.26.5 では解けなくなったため、
修正 PR https://github.com/TeamNewPipe/NewPipeExtractor/pull/1545（FineFindus/NewPipeExtractor の
`feat/update-n-sig`、コミット a462cb0）をローカルでビルドしたもの。

作り方（JDK 17 が要る: `brew install openjdk@17`）:

    git clone --branch feat/update-n-sig https://github.com/FineFindus/NewPipeExtractor.git
    cd NewPipeExtractor && sed -i '' 's/version = "v0.26.5"/version = "v0.26.5-nsig"/' build.gradle.kts
    JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew \
      :extractor:publishReleasePublicationToLocalRepository -x test -x checkstyleMain -x checkstyleTest \
      -Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    cp -R extractor/build/maven/net <この localMaven>/

上流で PR が取り込まれて新しいタグが出たら、`gradle/libs.versions.toml` を JitPack の
`com.github.TeamNewPipe:NewPipeExtractor:<tag>` に戻してこのディレクトリを消す。
