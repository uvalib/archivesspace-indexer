#
# Build extractor tool then run it. Convert output and combine. Upload to S3
#

MVN_CMD = mvn
JAVA_CMD = java
JAVA_OPTS = -Xms512M -Xmx512M
AWS_SYNC_CMD = aws s3 sync
YEAR = `date +%Y`
INDEX_DIR=results/index-v4

build: 
	$(MVN_CMD) clean install dependency:copy-dependencies -DskipTests

dirs:
	mkdir -p results/catalog/xml
	mkdir -p results/logs
	mkdir -p results/marc
	mkdir -p $(INDEX_DIR)

clean:
	-rm -rf $(INDEX_DIR)/*

extract:
	$(JAVA_CMD) $(JAVA_OPTS) -cp target/as-to-virgo-1.0-SNAPSHOT.jar:target/dependency/* edu.virginia.lib.indexing.tools.IndexRecords

upload-staging:
	$(AWS_SYNC_CMD) $(INDEX_DIR)/ s3://virgo4-ingest-staging-inbound/doc-update/default/$(YEAR)/aspace/ --exact-timestamps --exclude "*" --include "*.xml"

upload-production:
	$(AWS_SYNC_CMD) $(INDEX_DIR)/ s3://virgo4-ingest-production-inbound/doc-update/default/$(YEAR)/aspace/ --exact-timestamps --exclude "*" --include "*.xml"

year:
	echo $(YEAR)

#
# end of file
#
