package com.benwh.test.gelfemitter;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.core.net.Severity;
import org.apache.logging.log4j.core.util.Charsets;
import org.apache.logging.log4j.core.util.KeyValuePair;
import org.apache.logging.log4j.status.StatusLogger;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

/**
 * Lays out Graylog Extended Log Format (GELF) 1.1 log events.
 * <p>
 * Compresses (compressionType = "GZIP" or "ZLIB") the JSON if log event data is larger than 1024 bytes (the
 * compressionThreshold). Does not implement chunking.
 * </p>
 * <p>
 * Configure as follows to send to a Graylog2 server:
 * </p>
 *
 * <pre>
 * &lt;Appenders&gt;
 *        &lt;Socket name="Graylog" protocol="udp" host="graylog.domain.com" port="12201"&gt;
 *            &lt;GelfLayoutOa host="someserver" compressionType="GZIP"&gt;
 *                &lt;KeyValuePair key="additionalField1" value="additional value 1"/&gt;
 *                &lt;KeyValuePair key="additionalField2" value="additional value 2"/&gt;
 *            &lt;/GelfLayoutOa&gt;
 *        &lt;/Socket&gt;
 * &lt;/Appenders&gt;
 * </pre>
 *
 * @see <a href="http://graylog2.org/gelf">GELF home page</a>
 * @see <a href="http://graylog2.org/resources/gelf/specification">GELF specification</a>
 */
@Plugin(name = "GelfLayoutOa", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE, printObject = true)
public final class GelfLayoutOa extends AbstractStringLayout {

    public static enum CompressionType {

        GZIP {
            @Override
            public DeflaterOutputStream createDeflaterOutputStream(final OutputStream os) throws IOException {
                return new GZIPOutputStream(os);
            }
        },
        ZLIB {
            @Override
            public DeflaterOutputStream createDeflaterOutputStream(final OutputStream os) throws IOException {
                return new DeflaterOutputStream(os);
            }
        },
        OFF {
            @Override
            public DeflaterOutputStream createDeflaterOutputStream(final OutputStream os) throws IOException {
                return null;
            }
        };

        public abstract DeflaterOutputStream createDeflaterOutputStream(OutputStream os) throws IOException;

    }

    private static final char C = ',';
    private static final char Q = '\"';
    private static final String QC = "\",";
    private static final String QU = "\"_";
    private static final long serialVersionUID = 1L;
    private static final BigDecimal TIME_DIVISOR = new BigDecimal(1000);

    @PluginFactory
    public static GelfLayoutOa createLayout(
            //@formatter:off
            @PluginAttribute("host") final String host,
            @PluginElement("AdditionalField") final KeyValuePair[] additionalFields,
            @PluginAttribute(value = "compressionType",
            	defaultString = "GZIP") final CompressionType compressionType) {
            // @formatter:on
        return new GelfLayoutOa(host, additionalFields, compressionType);
    }

    /**
     * http://en.wikipedia.org/wiki/Syslog#Severity_levels
     */
    static int formatLevel(final Level level) {
        return Severity.getSeverity(level).getCode();
    }

    static String formatThrowable(final Throwable throwable) {
        // stack traces are big enough to provide a reasonably large initial capacity here
        final StringWriter sw = new StringWriter(2048);
        final PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    static String formatTimestamp(final long timeMillis) {
        return new BigDecimal(timeMillis).divide(TIME_DIVISOR).toPlainString();
    }

    private final KeyValuePair[] additionalFields;

    private final CompressionType compressionType;

    private final String host;

    public GelfLayoutOa(final String host, final KeyValuePair[] additionalFields, final CompressionType compressionType) {
        super(Charsets.UTF_8);
        this.host = host;
        this.additionalFields = additionalFields;
        this.compressionType = compressionType;
    }

    private byte[] compress(final byte[] bytes) {
        try {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream(32);
            final DeflaterOutputStream stream = compressionType.createDeflaterOutputStream(baos);
            if (stream == null) {
                return bytes;
            }
            stream.write(bytes);
            stream.finish();
            stream.close();
            return baos.toByteArray();
        } catch (final IOException e) {
            StatusLogger.getLogger().error(e);
            return bytes;
        }
    }

    @Override
    public Map<String, String> getContentFormat() {
        return Collections.emptyMap();
    }

    @Override
    public String getContentType() {
        return "application/json" + "; charset=" + this.getCharset();
    }

    @Override
    public byte[] toByteArray(final LogEvent event) {
        final byte[] bytes = toSerializable(event).getBytes(getCharset());
        return compress(bytes);
    }

    @Override
    public String toSerializable(final LogEvent event) {
        final StringBuilder builder = new StringBuilder(256);
        final JsonStringEncoder jsonEncoder = JsonStringEncoder.getInstance();
        builder.append('{');
        builder.append("\"version\":\"1.1\",");
        builder.append("\"host\":\"").append(jsonEncoder.quoteAsString(host)).append(QC);
        builder.append("\"timestamp\":").append(formatTimestamp(event.getTimeMillis())).append(C);
        builder.append("\"level\":").append(formatLevel(event.getLevel())).append(C);
        if (event.getThreadName() != null) {
            builder.append("\"_thread\":\"").append(jsonEncoder.quoteAsString(event.getThreadName())).append(QC);
        }
        if (event.getLoggerName() != null) {
            builder.append("\"_logger\":\"").append(jsonEncoder.quoteAsString(event.getLoggerName())).append(QC);
        }

        for (final KeyValuePair additionalField : additionalFields) {
            builder.append(QU).append(jsonEncoder.quoteAsString(additionalField.getKey())).append("\":\"")
                    .append(jsonEncoder.quoteAsString(additionalField.getValue())).append(QC);
        }
        for (final Map.Entry<String, String> entry : event.getContextMap().entrySet()) {
            builder.append(QU).append(jsonEncoder.quoteAsString(entry.getKey())).append("\":\"")
                    .append(jsonEncoder.quoteAsString(entry.getValue())).append(QC);
        }
        if (event.getThrown() != null) {
            builder.append("\"full_message\":\"").append(jsonEncoder.quoteAsString(formatThrowable(event.getThrown())))
                    .append(QC);
        }

        builder.append("\"short_message\":\"")
                .append(jsonEncoder.quoteAsString(event.getMessage().getFormattedMessage())).append(Q);
        builder.append('}');
        return builder.toString();
    }
}

