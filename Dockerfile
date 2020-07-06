FROM adoptopenjdk/openjdk8:alpine-jre
MAINTAINER Paul Bruce <me@paulsbruce.io>
RUN mkdir /jars
COPY ./build/libs/neotys-selenium-server-*-all.jar /jars/
CMD [""]
