#!/usr/bin/env bash

# rendering mechanism
render_template() {
  eval "echo \"$(cat ${1})\""
}

# generate the config file from the template
render_template config/config.properties.template > config.properties

#
# end of file
#
