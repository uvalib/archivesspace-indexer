#
# entrypoint for the container
#

# generate the config file
./scripts/make-config.sh

# generate the ArchivesSpace extract
make dirs
make clean
make extract
res=$?
if [ ${res} -ne 0 ]; then
   exit ${res}
fi

# do the appropriate upload
make upload-${ENVIRONMENT}
res=$?
exit ${res}

#
# end of file
#
