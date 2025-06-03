FROM mcr.microsoft.com/devcontainers/base:bookworm

SHELL ["/bin/bash", "-c"]

RUN apt-get update && \
    apt-get install -y git generate-ninja ninja-build python3 python3-venv python3-pip maven gdb curl
RUN apt-get clean all

RUN mkdir -p /data

ENV MX_PATH=/data/mx \
    JAVA_HOME=/data/jdk \
    PATH="/data/mx/:$PATH" 

# Get the mx tool
RUN git clone -b ${MX_BRANCH} --depth 1 https://github.com/graalvm/mx.git ${MX_PATH}

# mx needs to be able to write files to its own directory
RUN chmod -R 777 /data/mx

# Copy graal project files temporarily for setup
# COPY . /tmp/graal
COPY .mx_vcs_root /tmp/graal/.mx_vcs_root
COPY common.json /tmp/graal/common.json
COPY ci.jsonnet /tmp/graal/ci.jsonnet
COPY .git /tmp/graal/.git

# Fetch the JDK
RUN ${MX_PATH}/mx -p /tmp/graal --java-home= fetch-jdk --jdk-id labsjdk-ce-latest --to /data/jdk-dl --alias ${JAVA_HOME}

# Create some shorthands for mx commands
RUN echo "alias aot='mx -p /workspace/graal/substratevm native-image'" >> /etc/bash.bashrc && \
    echo "alias vm='mx -p /workspace/graal/compiler vm'" >> /etc/bash.bashrc && \
    echo "alias build-aot='mx -p /workspace/graal/substratevm build'" >> /etc/bash.bashrc && \
    echo "alias build-jvm='mx -p /workspace/graal/compiler build'" >> /etc/bash.bashrc


# Cleanup
RUN rm -rf /tmp/*

ENV BASH_ENV="/usr/local/sdkman/bin/sdkman-init.sh"
ENTRYPOINT ["/bin/bash", "-c"]
