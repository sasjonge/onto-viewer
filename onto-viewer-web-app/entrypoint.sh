#!/bin/bash

for i in config/default_* ; do
 test -e config/${i#config/default_} || cp -av ${i} config/${i#config/default_}
done

# if no UPDATE_URL and no ontology files in "ontologies" directory then copy ontology files from "integration_tests/ontologies/"
if [ -z "${UPDATE_URL}" ] && [ $(find ontologies/ -type f | wc -l) -eq 0 ] ; then
 find integration_tests/ontologies/ -type f -exec cp -av "{}" ontologies/ \;
fi

echo "[INFO] Starting app.war..."
exec java -server -Xmx4G -XX:-UseGCOverheadLimit -Dserver.address=0.0.0.0 -Dserver.port=80 -Dserver.forward-headers-strategy=native -Dserver.tomcat.redirect-context-root=false \
	${UPDATE_URL:+ -Dapp.config.ontologies.download_directory=download -Dapp.config.updateUrl=${UPDATE_URL}} -jar /app.war --server.servlet.context-path=/${ONTPUB_FAMILY:-dev}/ontology
