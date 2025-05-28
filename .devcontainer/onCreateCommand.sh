#!/bin/bash

echo "export GRAALVM_HOME=$(sdk home java 24-graal)" >> ~/.bashrc
sdk install java labsjdk-ce /data/jdk
sdk default java labsjdk-ce
mx -p /workspace/graal/substratevm intellijinit
find /workspace/graal -name mxbuild -type d -print -exec rm -rf {} \;
