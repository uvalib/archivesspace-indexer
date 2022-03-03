<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema" exclude-result-prefixes="xs" version="2.0">
 
    <xsl:variable name="fieldMap">
        <map v3="id" v4="id"/>
        <map v3="shadowed_location_facet" v4="shadowed_location_f"/>
        <map v3="aspace_version_facet" v4="aspace_version_f"/>
        <map v3="main_title_display" v4="title_tsearch_stored"/>
        <map v3="title_text" v4="full_title_tsearchf_stored"/>
        <map v3="source_facet" v4="source_f_stored"/>
        <map v3="format_facet" v4="format_f_stored"/>
        <!-- These are handled by a special template
        <map v3="library_facet" v4="library_f_stored"/>
        <map v3="library_facet" v4="source_f_stored"/>
        -->
        <map v3="subject_facet" v4="subject_tsearchf_stored"/>
        <map v3="extent_display" v4="extent_tsearch_stored"/>
        <map v3="date_display" v4="published_display_tsearch_stored"/>
        <map v3="author_facet" v4="author_tsearchf_stored"/>
        <!-- special handling elsewhere   <map v3="special_collections_holding_display" v4="sc_availability_large_single" /> -->
        <map v3="note_display" v4="note_tsearch_stored"/>
        <!-- <map v3="online_url_display" v4="url_aspace_stored" /> special handling elsewhere -->
        <map v3="rs_uri_display" v4="rs_uri_a" />
    </xsl:variable>

    <xsl:output method="xml" indent="yes" omit-xml-declaration="yes"/>
    <xsl:template match="add">
        <add>
            <xsl:apply-templates select="*"/>
        </add>
    </xsl:template>
 
    <xsl:template match="doc">
        <doc>
            <field name="pool_f">archival</field>
            <field name="data_source_f_stored">archivespace</field>
            <field name="circulating_f">false</field>
            <field name="record_date_stored">
                <xsl:value-of select="current-dateTime()"/>
            </field>
            <xsl:variable name="online" select="field[@name = 'format_facet'][text() = 'Online']"/>
            <xsl:if test="$online">
                <field name="uva_availability_f_stored">Online</field>
                <field name="anon_availability_f_stored">Online</field>
            </xsl:if>
            <!-- <field name="uva_availability_f_stored">On shelf</field>
            <field name="anon_availability_f_stored">On shelf</field> -->
            <xsl:apply-templates select="*"/>
        </doc>
    </xsl:template>

    <xsl:template match="field[@name = 'call_number_facet']">
        <field name="call_number_tsearch_stored">
            <xsl:value-of select="translate(text(), 'ms', 'MS')"/>
        </field>
        <field name="mss_work_key_sort">
            <xsl:value-of select="translate(text(), 'ms ', 'MS_')"/>
        </field>
        <field name="work_title3_key_ssort">
            <xsl:value-of select="translate(text(), 'ms ', 'MS_')"/>
        </field>
        <field name="work_title2_key_ssort">
            <xsl:value-of select="translate(text(), 'ms ', 'MS_')"/>
        </field>
    </xsl:template>

    <xsl:template match="field[@name = 'date_multisort_i']">
        <field name="published_date">
            <xsl:value-of select="concat(text(), '-01-01T00:00:00Z')"/>
        </field>
    </xsl:template>

    <xsl:template match="field[@name = 'special_collections_holding_display']">
        <field name="sc_availability_large_single">
            <xsl:value-of
                select="replace(text(), '&quot;location&quot;', '&quot;current_location&quot;')"/>
        </field>
    </xsl:template>

    <xsl:template match="field[@name = 'online_url_display']">
        <field name="url_supp_a">
            <xsl:value-of select="text()"/>
        </field>
        <field name="url_label_supp_a">GUIDE TO THE COLLECTION AVAILABLE ONLINE</field>
    </xsl:template>
    
    <xsl:template match="field[@name = 'access_restrictions_display']">
        <field name="access_restrictions_tsearch_stored">
            <xsl:value-of select="replace(replace(replace(text(), '(\r|\\r)(\n|\\n)', ''), '^&quot;', ''), '&quot;$', '')"/>
        </field>
    </xsl:template>
    
    <xsl:template match="field[@name = 'library_facet']">
        <field name="library_f_stored">
            <xsl:value-of select="replace(text(), 'Law School', 'Law')"/>
        </field>
        <xsl:if test="text() = 'Special Collections'">
          <field name="source_f_stored">
            <xsl:value-of select="text()"/>
          </field>
        </xsl:if>
    </xsl:template>

    <xsl:template match="field">
        <xsl:variable name="value">
          <xsl:apply-templates select="node()" mode="copy" />
        </xsl:variable>
        <xsl:variable name="v3FieldName" select="@name"/>
        <xsl:for-each select="$fieldMap/map[@v3 = $v3FieldName]">
            <field>
                <xsl:attribute name="name" select="@v4"/>
                <xsl:value-of select="$value" />
            </field>
        </xsl:for-each>
        <xsl:variable name="mapEntry" select="$fieldMap/map[@v3 = $v3FieldName]"/>
        <xsl:if test="not($mapEntry)">
            <xsl:comment>Dropped unmapped V3 "<xsl:value-of select="$v3FieldName"/>" field.</xsl:comment>
        </xsl:if>

    </xsl:template>

    <xsl:template match="@* | node()" mode="copy">
        <xsl:copy>
            <xsl:apply-templates select="@* | node()"/>
        </xsl:copy>
    </xsl:template>


    <!-- ======================================================================= -->
    <!-- DEFAULT TEMPLATE                                                        -->
    <!-- ======================================================================= -->


    <xsl:template match="@* | node()">
        <xsl:apply-templates select="@* | node()"/>
    </xsl:template>

    <xsl:template match="@* | node()">
        <xsl:apply-templates select="@* | node()"/>
    </xsl:template>
</xsl:stylesheet>
