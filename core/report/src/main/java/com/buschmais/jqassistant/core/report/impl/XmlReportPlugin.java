package com.buschmais.jqassistant.core.report.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.buschmais.jqassistant.core.report.api.LanguageHelper;
import com.buschmais.jqassistant.core.report.api.ReportContext;
import com.buschmais.jqassistant.core.report.api.ReportException;
import com.buschmais.jqassistant.core.report.api.ReportPlugin;
import com.buschmais.jqassistant.core.report.api.ReportPlugin.Default;
import com.buschmais.jqassistant.core.report.api.model.*;
import com.buschmais.jqassistant.core.report.api.model.source.ArtifactLocation;
import com.buschmais.jqassistant.core.report.api.model.source.FileLocation;
import com.buschmais.jqassistant.core.rule.api.model.*;
import com.buschmais.xo.api.CompositeObject;

import com.sun.xml.txw2.output.IndentingXMLStreamWriter;
import lombok.extern.slf4j.Slf4j;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static java.util.Collections.emptyMap;

/**
 * Implementation of {@link ReportPlugin} which writes the results of an
 * analysis to an XML file.
 */
@Slf4j
@Default
public class XmlReportPlugin implements ReportPlugin {

    // Properties
    public static final String XML_REPORT_FILE = "xml.report.file";

    // Default values
    public static final String DEFAULT_XML_REPORT_FILE = "jqassistant-report.xml";

    public static final String NAMESPACE_URL = "http://schema.jqassistant.org/report/v2.7";

    private static final Pattern XML_10_INVALID_CHARACTERS = Pattern.compile("[^\t\r\n -\uD7FF\uE000-�\uD800\uDC00-\uDBFF\uDFFF]");

    private XMLOutputFactory xmlOutputFactory;

    private XMLStreamWriter xmlStreamWriter;

    private ReportContext reportContext;

    private File xmlReportFile;

    private Map<Map.Entry<Concept, Boolean>, Result.Status> requiredConceptResults;

    private Map<Concept, Result.Status> providingConceptResults;

    private Result<? extends ExecutableRule> result;

    private long groupBeginTime;

    private long ruleBeginTime;

    private static final DateFormat XML_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    public void initialize() {
        this.xmlOutputFactory = XMLOutputFactory.newInstance();
    }

    @Override
    public void configure(ReportContext reportContext, Map<String, Object> properties) {
        this.reportContext = reportContext;
        String xmlReport = (String) properties.get(XML_REPORT_FILE);
        this.xmlReportFile = xmlReport != null ? new File(xmlReport) : new File(reportContext.getOutputDirectory(), DEFAULT_XML_REPORT_FILE);
    }

    @Override
    public void begin() throws ReportException {
        xml(() -> {
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(xmlReportFile), UTF_8);
            XMLStreamWriter streamWriter = xmlOutputFactory.createXMLStreamWriter(writer);
            xmlStreamWriter = new IndentingXMLStreamWriter(streamWriter);
            xmlStreamWriter.writeStartDocument(UTF_8.name(), "1.0");
            xmlStreamWriter.setDefaultNamespace(NAMESPACE_URL);
            xmlStreamWriter.writeStartElement("jqassistant-report");
            xmlStreamWriter.writeDefaultNamespace(NAMESPACE_URL);
            writeContext();
        });
    }

    private void writeContext() throws XMLStreamException {
        xmlStreamWriter.writeStartElement("context");
        xmlStreamWriter.writeStartElement("build");
        writeElementWithCharacters("name", reportContext.getBuild()
            .name());
        writeElementWithCharacters("timestamp", ISO_OFFSET_DATE_TIME.format(reportContext.getBuild()
            .timestamp()));
        xmlStreamWriter.writeStartElement("properties");
        for (Map.Entry<String, String> entry : new TreeMap<>(reportContext.getBuild()
            .properties()).entrySet()) {
            xmlStreamWriter.writeStartElement("property");
            xmlStreamWriter.writeAttribute("key", entry.getKey());
            xmlStreamWriter.writeCharacters(XML_10_INVALID_CHARACTERS.matcher(entry.getValue())
                .replaceAll(""));
            xmlStreamWriter.writeEndElement(); // property
        }
        xmlStreamWriter.writeEndElement(); // properties
        xmlStreamWriter.writeEndElement(); // build
        xmlStreamWriter.writeEndElement(); // context
    }

    @Override
    public void end() throws ReportException {
        xml(() -> {
            xmlStreamWriter.writeEndElement();
            xmlStreamWriter.writeEndDocument();
            xmlStreamWriter.close();
        });
    }

    @Override
    public void beginConcept(Concept concept, Map<Map.Entry<Concept, Boolean>, Result.Status> requiredConceptResults,
        Map<Concept, Result.Status> providingConceptResults) {
        beginExecutable(requiredConceptResults, providingConceptResults);
    }

    @Override
    public void endConcept() throws ReportException {
        endRule();
    }

    @Override
    public void beginGroup(final Group group) throws ReportException {
        final Date now = new Date();
        xml(() -> {
            xmlStreamWriter.writeStartElement("group");
            xmlStreamWriter.writeAttribute("id", group.getId());
            xmlStreamWriter.writeAttribute("date", XML_DATE_FORMAT.format(now));
        });
        this.groupBeginTime = now.getTime();
    }

    @Override
    public void endGroup() throws ReportException {
        xml(() -> {
            writeDuration(groupBeginTime);
            xmlStreamWriter.writeEndElement();
        });
    }

    @Override
    public void beginConstraint(Constraint constraint, Map<Map.Entry<Concept, Boolean>, Result.Status> requiredConceptResults) {
        beginExecutable(requiredConceptResults, emptyMap());
    }

    @Override
    public void endConstraint() throws ReportException {
        endRule();
    }

    @Override
    public void setResult(final Result<? extends ExecutableRule> result) {
        this.result = result;
    }

    private void beginExecutable(Map<Map.Entry<Concept, Boolean>, Result.Status> requiredConceptResults, Map<Concept, Result.Status> providingConceptResults) {
        this.ruleBeginTime = System.currentTimeMillis();
        this.requiredConceptResults = requiredConceptResults;
        this.providingConceptResults = providingConceptResults;
    }

    private void endRule() throws ReportException {
        if (result != null) {
            final ExecutableRule<?> rule = result.getRule();
            final String elementName;
            if (rule instanceof Concept) {
                elementName = "concept";
            } else if (rule instanceof Constraint) {
                elementName = "constraint";
            } else {
                throw new ReportException("Cannot write report for unsupported rule " + rule);
            }
            final List<String> columnNames = result.getColumnNames();
            final String primaryColumn = getPrimaryColumn(rule, columnNames);
            xml(() -> {
                xmlStreamWriter.writeStartElement(elementName);
                xmlStreamWriter.writeAttribute("id", rule.getId());
                writeElementWithCharacters("description", rule.getDescription());
                writeResult(columnNames, primaryColumn);
                writeReports(rule);
                writeVerificationResult(result.getVerificationResult());
                writeStatus(result.getStatus()); // status
                writeSeverity(result.getSeverity()); // severity
                writeDuration(ruleBeginTime);
                writeRequiredConceptResults(); // required-concept
                writeProvidingConceptResults(); // providing-concept
                xmlStreamWriter.writeEndElement(); // concept|constraint
            });
        }
    }

    private void writeVerificationResult(VerificationResult verificationResult) throws XMLStreamException {
        xmlStreamWriter.writeStartElement("verificationResult");
        writeElementWithCharacters("success", Boolean.toString(verificationResult.isSuccess()));
        writeElementWithCharacters("rowCount", Integer.toString(verificationResult.getRowCount()));
        xmlStreamWriter.writeEndElement(); // verificationResult
    }

    private void writeResult(List<String> columnNames, String primaryColumn) throws XMLStreamException {
        if (!result.isEmpty()) {
            xmlStreamWriter.writeStartElement("result");
            xmlStreamWriter.writeStartElement("columns");
            xmlStreamWriter.writeAttribute("count", Integer.toString(columnNames.size()));
            xmlStreamWriter.writeAttribute("primary", primaryColumn);
            for (String column : columnNames) {
                xmlStreamWriter.writeStartElement("column");
                xmlStreamWriter.writeCharacters(column);
                xmlStreamWriter.writeEndElement(); // column
            }
            xmlStreamWriter.writeEndElement(); // columns
            xmlStreamWriter.writeStartElement("rows");
            List<Row> rows = result.getRows();
            xmlStreamWriter.writeAttribute("count", Integer.toString(rows.size()));
            for (Row row : rows) {
                xmlStreamWriter.writeStartElement("row");
                xmlStreamWriter.writeAttribute("key", row.getKey());
                for (Map.Entry<String, Column<?>> rowEntry : row.getColumns()
                    .entrySet()) {
                    String columnName = rowEntry.getKey();
                    Column<?> column = rowEntry.getValue();
                    writeColumn(columnName, column);
                }
                xmlStreamWriter.writeEndElement();
            }
            xmlStreamWriter.writeEndElement(); // rows
            xmlStreamWriter.writeEndElement(); // result
        }
    }

    private void writeReports(ExecutableRule<?> rule) throws ReportException {
        List<ReportContext.Report<?>> reports = reportContext.getReports(rule);
        if (!reports.isEmpty()) {
            xml(() -> {
                xmlStreamWriter.writeStartElement("reports");
                for (ReportContext.Report<?> report : reports) {
                    ReportContext.ReportType reportType = report.getReportType();
                    switch (reportType) {
                    case LINK:
                        xmlStreamWriter.writeStartElement("link");
                        break;
                    case IMAGE:
                        xmlStreamWriter.writeStartElement("image");
                        break;
                    default:
                        throw new ReportException("Unsupported report type: " + reportType);
                    }
                    xmlStreamWriter.writeAttribute("label", report.getLabel());
                    xmlStreamWriter.writeCharacters(report.getUrl()
                        .toString());
                    xmlStreamWriter.writeEndElement();
                }
                xmlStreamWriter.writeEndElement();
            });
        }
    }

    public File getXmlReportFile() {
        return xmlReportFile;
    }

    /**
     * Determine the primary column for a rule, i.e. the colum used by tools like
     * SonarQube to attach issues.
     *
     * @param rule
     *     The {@link ExecutableRule}.
     * @param columnNames
     *     The column names returned by the executed rule.
     * @return The name of the primary column.
     */
    private String getPrimaryColumn(ExecutableRule<?> rule, List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            return null;
        }
        String primaryColumn = rule.getReport()
            .getPrimaryColumn();
        String firstColumn = columnNames.get(0);
        if (primaryColumn == null) {
            // primary column not explicitly specifed by the rule, so take the first column by default.
            return firstColumn;
        }
        if (!columnNames.contains(primaryColumn)) {
            log.warn("Rule '{}' defines primary column '{}' which is not provided by the result (available columns: {}). Falling back to '{}'.", rule,
                primaryColumn, columnNames, firstColumn);
            primaryColumn = firstColumn;
        }
        return primaryColumn;
    }

    /**
     * Write the status of the current result.
     *
     * @throws XMLStreamException
     *     If a problem occurs.
     */
    private void writeStatus(Result.Status status) throws XMLStreamException {
        writeElementWithCharacters("status", status.name()
            .toLowerCase());
    }

    /**
     * Determines the language and language element of a descriptor from a result
     * column.
     *
     * @param columnName
     *     The name of the column.
     * @param column
     *     The {@link Column}.
     * @throws XMLStreamException
     *     If a problem occurs.
     */
    private void writeColumn(String columnName, Column<?> column) throws XMLStreamException {
        xmlStreamWriter.writeStartElement("column");
        xmlStreamWriter.writeAttribute("name", columnName);
        Object value = column.getValue();
        if (value instanceof CompositeObject) {
            CompositeObject descriptor = (CompositeObject) value;
            Optional<LanguageElement> languageElement = LanguageHelper.getLanguageElement(descriptor);
            if (languageElement.isPresent()) {
                LanguageElement elementValue = languageElement.get();
                xmlStreamWriter.writeStartElement("element");
                xmlStreamWriter.writeAttribute("language", elementValue.getLanguage());
                xmlStreamWriter.writeCharacters(elementValue.name());
                xmlStreamWriter.writeEndElement(); // element
                Optional<FileLocation> sourceLocation = elementValue.getSourceProvider()
                    .getSourceLocation(descriptor);
                if (sourceLocation.isPresent()) {
                    xmlStreamWriter.writeStartElement("source");
                    writeSourceLocation(sourceLocation.get());
                    xmlStreamWriter.writeEndElement(); // sourceFile
                }
            }
        }
        writeElementWithCharacters("value", column.getLabel());
        xmlStreamWriter.writeEndElement(); // column
    }

    private void writeSourceLocation(FileLocation sourceLocation) throws XMLStreamException {
        xmlStreamWriter.writeAttribute("fileName", sourceLocation.getFileName());
        writeOptionalIntegerAttribute("startLine", sourceLocation.getStartLine());
        writeOptionalIntegerAttribute("endLine", sourceLocation.getEndLine());
        writeParentLocation(sourceLocation.getParent());
    }

    private void writeParentLocation(Optional<ArtifactLocation> optionalArtifactLocation) throws XMLStreamException {
        if (optionalArtifactLocation.isPresent()) {
            ArtifactLocation artifactLocation = optionalArtifactLocation.get();
            xmlStreamWriter.writeStartElement("parent");
            xmlStreamWriter.writeAttribute("fileName", artifactLocation.getFileName());
            writeOptionalStringAttribute("group", artifactLocation.getGroup());
            writeOptionalStringAttribute("name", artifactLocation.getName());
            writeOptionalStringAttribute("type", artifactLocation.getType());
            writeOptionalStringAttribute("version", artifactLocation.getVersion());
            writeOptionalStringAttribute("classifier", artifactLocation.getClassifier());
            writeParentLocation(artifactLocation.getParent());
            xmlStreamWriter.writeEndElement();
        }
    }

    private void writeElementWithCharacters(String element, String text) throws XMLStreamException {
        xmlStreamWriter.writeStartElement(element);
        if (text != null) {
            xmlStreamWriter.writeCharacters(XML_10_INVALID_CHARACTERS.matcher(text)
                .replaceAll(""));
        }
        xmlStreamWriter.writeEndElement();
    }

    private void writeOptionalIntegerAttribute(String attribute, Optional<Integer> value) throws XMLStreamException {
        if (value.isPresent()) {
            xmlStreamWriter.writeAttribute(attribute, value.get()
                .toString());
        }
    }

    private void writeOptionalStringAttribute(String attribute, Optional<String> value) throws XMLStreamException {
        if (value.isPresent()) {
            xmlStreamWriter.writeAttribute(attribute, value.get());
        }
    }

    /**
     * Writes the duration.
     *
     * @param beginTime
     *     The begin time.
     * @throws XMLStreamException
     *     If writing fails.
     */
    private void writeDuration(long beginTime) throws XMLStreamException {
        writeElementWithCharacters("duration", Long.toString(System.currentTimeMillis() - beginTime));
    }

    /**
     * Writes the severity of the rule.
     *
     * @param severity
     *     The severity the rule has been executed with
     * @throws XMLStreamException
     *     If writing fails.
     */
    private void writeSeverity(Severity severity) throws XMLStreamException {
        xmlStreamWriter.writeStartElement("severity");
        xmlStreamWriter.writeAttribute("level", severity.getLevel()
            .toString());
        xmlStreamWriter.writeCharacters(severity.getValue());
        xmlStreamWriter.writeEndElement();
    }

    private void writeRequiredConceptResults() throws XMLStreamException {
        if (requiredConceptResults != null) {
            for (Map.Entry<Map.Entry<Concept, Boolean>, Result.Status> entry : requiredConceptResults.entrySet()) {
                xmlStreamWriter.writeStartElement("required-concept");
                Concept concept = entry.getKey()
                    .getKey();
                xmlStreamWriter.writeAttribute("id", concept.getId());
                writeStatus(entry.getValue());
                xmlStreamWriter.writeEndElement();
            }
        }
    }

    private void writeProvidingConceptResults() throws XMLStreamException {
        if (providingConceptResults != null) {
            for (Map.Entry<Concept, Result.Status> entryStatusEntry : providingConceptResults.entrySet()) {
                xmlStreamWriter.writeStartElement("providing-concept");
                Concept concept = entryStatusEntry.getKey();
                xmlStreamWriter.writeAttribute("id", concept.getId());
                writeStatus(entryStatusEntry.getValue());
                xmlStreamWriter.writeEndElement();
            }
        }
    }

    /**
     * Defines an operation to write XML elements.
     *
     * @param operation
     *     The operation.
     * @throws ReportException
     *     If writing fails.
     */
    private void xml(XmlOperation operation) throws ReportException {
        try {
            operation.run();
        } catch (XMLStreamException | IOException e) {
            throw new ReportException("Cannot write to XML report.", e);
        }
    }

    private interface XmlOperation {
        void run() throws XMLStreamException, IOException, ReportException;
    }

}
