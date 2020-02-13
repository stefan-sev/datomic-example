#!/bin/bash

BASE_DIR=$(pwd)

cd /Users/stefan-sev/clojure/datomic-pro-0.9.5930 

bin/transactor config/samples/dev-transactor-template.properties &

TRANSACTOR_PID=$!

sleep 10
echo "started transactor"

bin/run -m datomic.peer-server -h localhost -p 8998 -a admin,pw -d bank,datomic:dev://localhost:4334/bank &

PEER_PID=$!

echo "started peer"

echo ${BASE_DIR}
cd ${BASE_DIR}
touch .datomic.pid
echo ${TRANSACTOR_PID} > .datomic.pid
echo ${PEER_PID} >> .datomic.pid
exit 0


