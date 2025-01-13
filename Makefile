#
# Build extractor tool then run it. Convert output and combine. Upload to S3
#

MVN_CMD = mvn
JAVA_CMD = java
JAVA_OPTS = -Xms1024M -Xmx1024M
AWS_SYNC_CMD = aws s3 sync
YEAR = `date +%Y`
INDEX_DIR=results/index-v4
INDEX_PRODUCTION_DIR=results/index-uploaded-production
INDEX_STAGING_DIR=results/index-uploaded-staging
TO_DELETE_PRODUCTION=results/to_delete_production.ids
TO_DELETE_STAGING=results/to_delete_staging.ids

PRODUCTION_SOLR_URL=http://v4-solr-production-replica-0-private.internal.lib.virginia.edu:8080/solr/test_core
STAGING_SOLR_URL=http://virgo4-solr-staging-replica-0-private.internal.lib.virginia.edu:8080/solr/test_core
SOLR_QUERY=/select?fl=id&q=data_source_f%3Aarchivespace&rows=5000
PRODUCTION_BUCKET_BASE=s3://virgo4-ingest-production-inbound/
STAGING_BUCKET_BASE=s3://virgo4-ingest-staging-inbound/
UPDATE_BUCKET=doc-update/default/$(YEAR)/aspace/
DELETE_BUCKET_NAME=doc-delete/default/$(YEAR)/archivesspace_delete_`date +%Y%m%d`.ids

build: 
	$(MVN_CMD) clean install dependency:copy-dependencies -DskipTests

dirs:
	mkdir -p results/catalog/xml
	mkdir -p results/logs
	mkdir -p results/marc
	mkdir -p $(INDEX_DIR)

clean:
	-rm -rf $(INDEX_DIR)/*

make-config:
	-mv config.properties config.properties.prev
	bin/make_config_from_template_and_params > config.properties

extract:
	$(JAVA_CMD) $(JAVA_OPTS) -cp target/as-to-virgo-1.0-SNAPSHOT.jar:target/dependency/* edu.virginia.lib.indexing.tools.IndexRecords config.properties

extract-all:
	rm -f $(INDEX_DIR)/*.xml
	cat config.properties | sed -e 's/interval:.*/interval:-1/' > config_all.properties
	$(JAVA_CMD) $(JAVA_OPTS) -cp target/as-to-virgo-1.0-SNAPSHOT.jar:target/dependency/* edu.virginia.lib.indexing.tools.IndexRecords config_all.properties
	

upload-production:
	$(AWS_SYNC_CMD) $(PRODUCTION_BUCKET_BASE)$(UPDATE_BUCKET) $(INDEX_PRODUCTION_DIR)/ --exclude "*" --include "*.xml"
	./copy_new_from_all.sh $(INDEX_PRODUCTION_DIR) $(INDEX_DIR)
	$(AWS_SYNC_CMD) $(INDEX_PRODUCTION_DIR)/ $(PRODUCTION_BUCKET_BASE)$(UPDATE_BUCKET) --delete --exclude "*" --include "*.xml"

upload-staging:
	$(AWS_SYNC_CMD) $(STAGING_BUCKET_BASE)$(UPDATE_BUCKET) $(INDEX_STAGING_DIR)/ --exclude "*" --include "*.xml"
	./copy_new_from_all.sh $(INDEX_STAGING_DIR) $(INDEX_DIR)
	$(AWS_SYNC_CMD) $(INDEX_STAGING_DIR)/ $(STAGING_BUCKET_BASE)$(UPDATE_BUCKET) --delete --exclude "*" --include "*.xml"

check-deletes-production:
	curl -s "$(PRODUCTION_SOLR_URL)$(SOLR_QUERY)" | egrep '"id":' | sed -e 's/^.*"id":"//' -e 's/".*$$//' | sort > results/in_solr_production.ids
	find $(INDEX_DIR) -type f | sed -e 's/^.*as_/as_/' -e 's/.xml$$//' -e 's/_/:/' | sort > results/indexed.ids
	diff --side-by-side results/in_solr_production.ids results/indexed.ids | egrep '<' | sed -e 's/[ \t].*$$//' > $(TO_DELETE_PRODUCTION)
	if [[ -s  $(TO_DELETE_PRODUCTION) ]] ; then \
	    aws s3 cp $(TO_DELETE_PRODUCTION) $(PRODUCTION_BUCKET_BASE)$(DELETE_BUCKET_NAME); \
	fi

check-deletes-staging:
	curl -s "$(STAGING_SOLR_URL)$(SOLR_QUERY)" | egrep '"id":' | sed -e 's/^.*"id":"//' -e 's/".*$$//' | sort > results/in_solr_staging.ids
	find $(INDEX_DIR) -type f | sed -e 's/^.*as_/as_/' -e 's/.xml$$//' -e 's/_/:/' | sort > results/indexed.ids
	diff --side-by-side results/in_solr_staging.ids results/indexed.ids | egrep '<' | sed -e 's/[ \t].*$$//' > $(TO_DELETE_STAGING)
	if [[ -s  $(TO_DELETE_STAGING) ]] ; then \
	    aws s3 cp $(TO_DELETE_STAGING) $(STAGING_BUCKET_BASE)$(DELETE_BUCKET_NAME); \
	fi

year:
	echo $(YEAR)

#
# end of file
#
