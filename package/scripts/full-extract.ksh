#!/usr/bin/env bash
#
# script for the full extract
#

# generate the config file
./scripts/make-config.sh

# kind of a hack
if [ -d /mnt/crontab-runner/results ]; then
   ln -s /mnt/crontab-runner/results .
fi

# generate the ArchivesSpace extract
make dirs
make extract-all
res=$?
if [ ${res} -ne 0 ]; then
   exit ${res}
fi

# do the appropriate upload
make upload-${UPLOAD_ENVIRONMENT}
res=$?
exit ${res}

#
# end of file
#
