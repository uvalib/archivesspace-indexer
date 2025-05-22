#!/usr/bin/env bash
#
# common helpers used by the scripting
#

# print a message and exit
function report_and_exit {

   local MESSAGE=$1
   echo $MESSAGE
   exit 1
}

# print an error message and exit
function error_and_exit {

   local MESSAGE="ERROR: $1"
   report_and_exit "$MESSAGE"
}

# ensure the specific tool is available
function ensure_tool_available {

   local TOOL_NAME=$1
   which $TOOL_NAME > /dev/null 2>&1
   res=$?
   if [ $res -ne 0 ]; then
      error_and_exit "$TOOL_NAME is not available in this environment"
   fi
}

# validate a value is in a set of possible values (pipe separated)
function validate_in {

   local VALUE=$1
   local SET=$2

   [[ "$VALUE" =~ ^($SET) ]]
}

#
# end of file
#
