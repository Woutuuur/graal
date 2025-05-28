#!/bin/bash

echo "export GRAALVM_HOME=$(sdk home java 24-graal)" >> ~/.bashrc
sdk install java labsjdk-ce /data/jdk
sdk default java labsjdk-ce
mx -p /workspace/graal/substratevm intellijinit
find /workspace/graal -name mxbuild -print -type d -exec rm -rf {} \;

exit 0
