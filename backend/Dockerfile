# syntax=docker/dockerfile:1
FROM gradle:8-jdk21 AS build
WORKDIR /workspace

COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon

COPY src src
RUN gradle bootJar --no-daemon -x test

RUN find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN addgroup --system app && adduser --system --ingroup app app
COPY --from=build /workspace/app.jar app.jar
# 올라온 파일(가게 사진·사업자등록증)을 두는 폴더. /app 은 root 소유라 app 사용자가 쓸 수 없어 따로 만들어 준다.
# 여기에 볼륨을 붙이면(docker-compose.yml) 처음 만들어질 때 이 소유권을 그대로 물려받는다.
RUN mkdir /app/uploads && chown app:app app.jar /app/uploads
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
