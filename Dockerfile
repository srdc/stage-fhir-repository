FROM eclipse-temurin:11-jdk

RUN apt-get update && \
    apt-get install dos2unix && \
    apt-get clean

# Create folders
RUN mkdir -p /build

# Add Files
ADD target/stage-fhir-repository-standalone.jar /build
ADD docker-entrypoint.sh /build

RUN chown root:root /build/docker-entrypoint.sh
RUN chmod +x /build/docker-entrypoint.sh

# Convert line endings
RUN dos2unix /build/docker-entrypoint.sh

EXPOSE 8080
EXPOSE 8081
ENTRYPOINT ["/build/docker-entrypoint.sh"]
