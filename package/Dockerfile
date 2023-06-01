# 
# build the target application
#
FROM public.ecr.aws/docker/library/amazoncorretto:17-al2023-jdk as builder
ENV JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto.x86_64

# update and install dependancies
RUN yum -y update && yum -y install maven make

# copy assets
WORKDIR /build
COPY src ./src
COPY pom.xml ./
COPY Makefile ./

# build the target 
RUN make build

# base image
FROM public.ecr.aws/docker/library/amazoncorretto:17-al2023-jdk
ENV JAVA_HOME=/usr/lib/jvm/java-17-amazon-corretto.x86_64

# so we can install new packages
RUN yum -y update && yum -y install make awscli

WORKDIR /archivesspace-virgo
RUN mkdir config scripts
COPY Makefile ./
COPY config/config.properties.template ./config/
COPY package/scripts/make-config.sh ./scripts/
COPY package/scripts/entry.sh ./scripts/

# copy the built assets
COPY --from=builder /build/target ./target


#
# end of file
#
