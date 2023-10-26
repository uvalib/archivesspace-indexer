#
# Build extractor tool then run it. Convert output and combine. Upload to S3
#

MVN_CMD = mvn
JAVA_CMD = java
JAVA_OPTS = -Xms512M -Xmx512M
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
PRODUCTION_S3_DELETE=s3://virgo4-ingest-production-inbound/doc-delete/default/2023/archivesspace_delete_production_`date +%Y%m%d`.ids
STAGING_S3_DELETE=s3://virgo4-ingest-staging-inbound/doc-delete/default/2023/archivesspace_delete_staging_`date +%Y%m%d`.ids

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
	$(JAVA_CMD) $(JAVA_OPTS) -cp target/as-to-virgo-1.0-SNAPSHOT.jar:target/dependency/* edu.virginia.lib.indexing.tools.IndexRecords config.properties

extract_all:
	rm -f $(INDEX_DIR)/*.xml
	cat config.properties | sed -e 's/interval:.*/interval:-1/' > config_all.properties
	$(JAVA_CMD) $(JAVA_OPTS) -cp target/as-to-virgo-1.0-SNAPSHOT.jar:target/dependency/* edu.virginia.lib.indexing.tools.IndexRecords config_all.properties
	

upload-staging:
	$(AWS_SYNC_CMD) s3://virgo4-ingest-staging-inbound/doc-update/default/$(YEAR)/aspace/ $(INDEX_STAGING_DIR)/ --exclude "*" --include "*.xml"
	./copy_new_from_all.sh $(INDEX_STAGING_DIR) $(INDEX_DIR)
	$(AWS_SYNC_CMD) $(INDEX_STAGING_DIR)/ s3://virgo4-ingest-staging-inbound/doc-update/default/$(YEAR)/aspace/ --delete --exclude "*" --include "*.xml"

upload-production:
	$(AWS_SYNC_CMD) s3://virgo4-ingest-production-inbound/doc-update/default/$(YEAR)/aspace/ $(INDEX_PRODUCTION_DIR)/ --exclude "*" --include "*.xml"
	./copy_new_from_all.sh $(INDEX_PRODUCTION_DIR) $(INDEX_DIR)
	$(AWS_SYNC_CMD) $(INDEX_PRODUCTION_DIR)/ s3://virgo4-ingest-production-inbound/doc-update/default/$(YEAR)/aspace/ --delete --exclude "*" --include "*.xml"

check-deletes-production:
	curl -s "$(PRODUCTION_SOLR_URL)$(SOLR_QUERY)" | egrep '"id":' | sed -e 's/^.*"id":"//' -e 's/".*$$//' | sort > results/in_solr_production.ids
	find $(INDEX_DIR) -type f | sed -e 's/^.*as_/as_/' -e 's/.xml$$//' -e 's/_/:/' | sort > results/indexed.ids
	diff --side-by-side results/in_solr_production.ids results/indexed.ids | egrep '<' | sed -e 's/[ \t].*$$//' > $(TO_DELETE_PRODUCTION)
	if [[ -s  $(TO_DELETE_PRODUCTION) ]] ; then \
	    aws s3 cp $(TO_DELETE_PRODUCTION) $(PRODUCTION_S3_DELETE); \
	fi

check-deletes-staging:
	curl -s "$(STAGING_SOLR_URL)$(SOLR_QUERY)" | egrep '"id":' | sed -e 's/^.*"id":"//' -e 's/".*$$//' | sort > results/in_solr_staging.ids
	find $(INDEX_DIR) -type f | sed -e 's/^.*as_/as_/' -e 's/.xml$$//' -e 's/_/:/' | sort > results/indexed.ids
	diff --side-by-side results/in_solr_staging.ids results/indexed.ids | egrep '<' | sed -e 's/[ \t].*$$//' > $(TO_DELETE_STAGING)
	if [[ -s  $(TO_DELETE_STAGING) ]] ; then \
	    aws s3 cp $(TO_DELETE_STAGING) $(STAGING_S3_DELETE); \
	fi

year:
	echo $(YEAR)

#
# end of file
#
