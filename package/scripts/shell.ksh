#if [ -z "$DOCKER_HOST" ]; then
#   echo "ERROR: no DOCKER_HOST defined"
#   exit 1
#fi

if [ -z "$DOCKER_HOST" ]; then
   DOCKER_TOOL=docker
else
   DOCKER_TOOL=legacy
fi

# set the definitions
INSTANCE=archivesspace-indexer
NAMESPACE=uvadave

$DOCKER_TOOL run -ti -v /Users/dpg3k/Sandboxes/fupload/tmp:/shanti $NAMESPACE/$INSTANCE /bin/sh -l
