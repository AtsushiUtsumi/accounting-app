# accounting-app の実行イメージ。
#
# jar はこの Dockerfile の外で先に作っておく(./gradlew bootJar)。
# アプリは ../accounting-domain を composite build で取り込んでいて、Docker のビルドコンテキストからは
# ドメインが見えないため、ここでは Gradle を動かさず、できあがった jar をコピーするだけにしている。
#
# ビルドと push は build-image.bat(手元)または .github/workflows/image.yml(GitHub Actions)で行う。

FROM eclipse-temurin:25-jre

LABEL org.opencontainers.image.source="https://github.com/AtsushiUtsumi/accounting-app" \
      org.opencontainers.image.description="accounting-app (Spring Boot)"

# root 以外のユーザーで動かす(そのため 1024 未満のポートは開けない。コンテナ内は 8080 番で待ち受ける)
RUN groupadd --system app && useradd --system --gid app --no-create-home app

WORKDIR /app
COPY build/libs/accounting-app.jar app.jar

USER app
ENV ACCOUNTING_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
