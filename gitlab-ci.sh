#!/bin/bash

docker login -u $DOCKER_REGISTRY_USER -p $DOCKER_REGISTRY_PASS registry.gitlab.com

cd server;

# if [ -n "$CODACY_PROJECT_TOKEN" ]; then
#  echo "Building from upstream. Codacy reporting enabled.";
#  sbt clean coverage test coverageReport codacyCoverage;
#else
#  echo "Building PR from fork. Codacy reporting disabled.";
  sbt clean coverage test coverageReport;
#fi

sbt docker:publish

