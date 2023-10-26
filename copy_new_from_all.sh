#!/bin/bash

test=
if [[ $1 == "-t" ]] ; then
    test=-t
    shift
fi
verbose=
if [[ $1 == "-v" ]] ; then
    verbose=-v
    shift
fi

INDEX_OLD_DIR=$1
INDEX_NEW_DIR=$2

for file in `find ${INDEX_OLD_DIR} -type f`
do
    fname=`basename $file`
    if [[ ! -e ${INDEX_NEW_DIR}/$fname ]] ; then
        id=`echo $fname | sed -e 's/_/:/' -e 's/.xml//'`
        if [[ "$test" == "-t" ]] ; then 
            echo delete $file
        else
            rm $file
        fi
    fi
done

for file in `find ${INDEX_NEW_DIR} -type f`
do
    fname=`basename $file`
    cmp -s ${INDEX_OLD_DIR}/$fname $file
    if [[ $? != 0 ]] ; then
        if [[ "$test" == "-t" ]] ; then 
            echo "move $file"
        else
            cp $file ${INDEX_OLD_DIR}/$fname 
        fi
    else  # the same
        if [[ "$test" == "-t" && "$verbose" != "-v" ]] ; then 
            echo "skip $file"
        fi
    fi
done

