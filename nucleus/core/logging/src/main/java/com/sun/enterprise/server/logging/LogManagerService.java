/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
 * Copyright (c) 2009, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.server.logging;

import com.sun.appserv.server.util.Version;
import com.sun.common.util.logging.LoggingConfigImpl;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.util.SystemPropertyConstants;
import com.sun.enterprise.util.io.FileUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.validation.ValidationException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.glassfish.api.VersionInfo;
import org.glassfish.api.admin.FileMonitoring;
import org.glassfish.common.util.Constants;
import org.glassfish.config.support.TranslatedConfigView;
import org.glassfish.hk2.api.Rank;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.runlevel.RunLevel;
import org.glassfish.internal.api.Globals;
import org.glassfish.internal.api.InitRunLevel;
import org.glassfish.internal.config.UnprocessedConfigListener;
import org.glassfish.main.jul.GlassFishLogManager;
import org.glassfish.main.jul.GlassFishLogManager.Action;
import org.glassfish.main.jul.GlassFishLogger;
import org.glassfish.main.jul.JULHelperFactory;
import org.glassfish.main.jul.cfg.GlassFishLogManagerConfiguration;
import org.glassfish.main.jul.cfg.LoggingProperties;
import org.glassfish.main.jul.env.LoggingSystemEnvironment;
import org.glassfish.main.jul.handler.GlassFishLogHandler;
import org.glassfish.main.jul.handler.GlassFishLogHandlerConfiguration;
import org.glassfish.main.jul.handler.GlassFishLogHandlerProperty;
import org.glassfish.main.jul.tracing.GlassFishLoggingTracer;
import org.glassfish.server.ServerEnvironmentImpl;
import org.jvnet.hk2.annotations.Service;

import static org.glassfish.main.jul.cfg.GlassFishLoggingConstants.JVM_OPT_LOGGING_CFG_FILE;
import static org.glassfish.main.jul.handler.GlassFishLogHandler.createGlassFishLogHandlerConfiguration;
import static org.glassfish.main.jul.handler.GlassFishLogHandlerProperty.MINIMUM_ROTATION_LIMIT_MB;
import static org.glassfish.main.jul.handler.GlassFishLogHandlerProperty.ROTATION_LIMIT_SIZE;
import static org.glassfish.main.jul.handler.GlassFishLogHandlerProperty.ROTATION_LIMIT_TIME;

/**
 * Reinitialize the log manager using our logging.properties file.
 *
 * @author Jerome Dochez
 * @author Carla Mott
 * @author Naman Mehta
 * @author David Matejcek
 */
@Service
@RunLevel(InitRunLevel.VAL)
@Rank(Constants.IMPORTANT_RUN_LEVEL_SERVICE)
public class LogManagerService implements org.glassfish.internal.api.LogManager {

    private static final Logger LOG = Logger.getLogger(LogManagerService.class.getName(), LogFacade.LOGGING_RB_NAME);

    @Inject
    ServerEnvironmentImpl env;

    @Inject
    FileMonitoring fileMonitoring;

    @Inject
    LoggingConfigImpl loggingConfig;

    @Inject
    UnprocessedConfigListener ucl;

    @Inject
    Domain domain;

    private static final Consumer<Map.Entry<String, String>> PROPERTY_VALUE_RESOLVER = e -> {
        Object value = TranslatedConfigView.getTranslatedValue(e.getValue());
        e.setValue(value == null ? null : value.toString());
    };

    /**
     * Initialize the loggers
     */
    @PostConstruct
    public void postConstruct() {
        if (!GlassFishLogManager.isGlassFishLogManager()) {
            LOG.info(() -> "Detected other than GlassFishLogManager, the LogManagerService's features may be limited."
                + " Used log manager: " + LogManager.getLogManager());
        }
        setProductId();

        final File loggingPropertiesFile = getOrCreateLoggingProperties();
        reconfigure(loggingPropertiesFile);
        LOG.config("Configuring change detection of the configuration file ...");
        fileMonitoring.monitors(loggingPropertiesFile, new LoggingCfgFileChangeListener(this::reconfigure));

        LOG.config("LogManagerService completed successfuly ...");
        LOG.log(Level.INFO, LogFacade.GF_VERSION_INFO, Version.getFullVersion());
    }


    /**
     * Returns properties based on the DAS/Cluster/Instance.
     * Values are not resolved, so can contain ${com.sun...} properties
     */
    @Override
    public Map<String, String> getLoggingProperties() throws IOException {
        final Server targetServer = domain.getServerNamed(env.getInstanceName());
        final Map<String, String> loggingProperties;
        if (targetServer == null) {
            loggingProperties = loggingConfig.getLoggingProperties();
        } else if (targetServer.isDas()) {
            loggingProperties = loggingConfig.getLoggingProperties();
        } else if (targetServer.getCluster() != null) {
            loggingProperties = loggingConfig.getLoggingProperties(targetServer.getCluster().getConfigRef());
        } else if (targetServer.isInstance()) {
            loggingProperties = loggingConfig.getLoggingProperties(targetServer.getConfigRef());
        } else {
            loggingProperties = loggingConfig.getLoggingProperties();
        }

        Map<String, String> invalidProps = validateLoggingProperties(loggingProperties);
        if (!invalidProps.isEmpty()) {
            return loggingConfig.deleteLoggingProperties(invalidProps.keySet());
        }

        return loggingProperties;
    }


    @Override
    public File getLoggingPropertiesFile() throws IOException {
        final Server targetServer = domain.getServerNamed(env.getInstanceName());
        if (targetServer == null) {
            return new File(env.getConfigDirPath(), ServerEnvironmentImpl.kLoggingPropertiesFileName);
        }
        if (targetServer.isDas()) {
            return new File(env.getConfigDirPath(), ServerEnvironmentImpl.kLoggingPropertiesFileName);
        }
        if (targetServer.getCluster() != null) {
            File dirForLogging = new File(env.getConfigDirPath(), targetServer.getCluster().getConfigRef());
            return new File(dirForLogging, ServerEnvironmentImpl.kLoggingPropertiesFileName);
        }
        if (targetServer.isInstance()) {
            File dirForLogging = new File(env.getConfigDirPath(), targetServer.getConfigRef());
            return new File(dirForLogging, ServerEnvironmentImpl.kLoggingPropertiesFileName);
        }
        return new File(env.getConfigDirPath(), ServerEnvironmentImpl.kLoggingPropertiesFileName);
    }


    @Override
    public void addHandler(Handler handler) {
        LOG.config(() -> "LogManagerService.addHandler(" + handler + ")");
        final GlassFishLogger rootLogger = getRootLogger();
        if (rootLogger != null && rootLogger.getHandler(handler.getClass()) == null) {
            rootLogger.addHandler(handler);
        }
    }


    @Override
    public PrintStream getErrStream() {
        return LoggingSystemEnvironment.getOriginalStdErr();
    }


    @Override
    public PrintStream getOutStream() {
        return LoggingSystemEnvironment.getOriginalStdOut();
    }


    /**
     * Validates the map of logging properties. Will remove any properties from the
     * map that don't pass the validation, and then throw an exception at the very
     * end.
     *
     * @param loggingProperties the map of properties to validate. WILL BE MODIFIED.
     * @return a map of invalid properties. Will never be null.
     */
    public Map<String, String> validateLoggingProperties(Map<String, String> loggingProperties) {
        Map<String, String> invalidProps = new HashMap<>();
        Iterator<Entry<String, String>> propertyIterator = loggingProperties.entrySet().iterator();
        while (propertyIterator.hasNext()) {
            Entry<String, String> propertyEntry = propertyIterator.next();
            try {
                validateLoggingProperty(propertyEntry.getKey(), propertyEntry.getValue());
            } catch (ValidationException ex) {
                LOG.log(Level.WARNING, "Error validating log property.", ex);
                invalidProps.put(propertyEntry.getKey(), propertyEntry.getValue());
            }
        }
        return invalidProps;
    }

    /**
     * Validates a property. Throws an exception if validation fails.
     *
     * @param key   the attribute name to validate.
     * @param value the attribute value to validate.
     * @throws ValidationException if validation fails.
     */
    public void validateLoggingProperty(String key, String value) {
        if (isOneOf(key, ROTATION_LIMIT_SIZE, GlassFishLogHandler.class)) {
            int rotationSizeLimit = Integer.parseInt(value);
            if (rotationSizeLimit != 0 && rotationSizeLimit < MINIMUM_ROTATION_LIMIT_MB) {
                throw new ValidationException(String.format("'%s' value must be greater than %d, but was %d.",
                    key, MINIMUM_ROTATION_LIMIT_MB, rotationSizeLimit));
            }
        } else if (isOneOf(key, ROTATION_LIMIT_TIME, GlassFishLogHandler.class)) {
            int rotationTimeLimit = Integer.parseInt(value);
            if (rotationTimeLimit < 0) {
                throw new ValidationException(String.format("'%s' value must be greater than %d, but was %d.",
                    key, 0, rotationTimeLimit));
            }
        }
    }


    @PreDestroy
    public void preDestroy() {
        LOG.config("Completed shutdown of Log manager service");
    }

    private void setProductId() {
        final ServiceLocator locator = Globals.getDefaultBaseServiceLocator();
        final VersionInfo versionInfo = locator.getService(VersionInfo.class);
        if (versionInfo == null) {
            LoggingSystemEnvironment.setProductId(null);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(versionInfo.getAbbreviatedProductName());
        sb.append(' ');
        sb.append(versionInfo.getVersionPrefix());
        sb.append(versionInfo.getMajorVersion());
        sb.append('.');
        sb.append(versionInfo.getMinorVersion());
        LoggingSystemEnvironment.setProductId(sb.toString());
    }


    private File getOrCreateLoggingProperties() {
        final String loggingPropertiesJvmOption = System.getProperty(JVM_OPT_LOGGING_CFG_FILE);
        LOG.finest(() -> "Logging configuration from JVM option " + JVM_OPT_LOGGING_CFG_FILE + "="
            + loggingPropertiesJvmOption);
        if (loggingPropertiesJvmOption == null) {
            return getExistingLoggingPropertiesFile();
        }
        return new File(loggingPropertiesJvmOption);
    }


    private File getExistingLoggingPropertiesFile() {
        try {
            final File configuredFile = getLoggingPropertiesFile();
            if (configuredFile.exists()) {
                return configuredFile;
            }
            final String rootFolder = env.getProps().get(SystemPropertyConstants.INSTALL_ROOT_PROPERTY);
            final String templateDir = rootFolder + File.separator + "lib" + File.separator + "templates";
            final File src = new File(templateDir, ServerEnvironmentImpl.kLoggingPropertiesFileName);
            final File dest = new File(env.getConfigDirPath(), ServerEnvironmentImpl.kLoggingPropertiesFileName);
            LOG.log(Level.INFO, "{0} not found, creating new file from template {1}.", new Object[] {dest, src});
            FileUtils.copy(src, dest);
            return dest;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, LogFacade.ERROR_READING_CONF_FILE, e);
            return null;
        }
    }


    private Handler[] getRootHandlers() {
        return getRootLogger().getHandlers();
    }


    private GlassFishLogger getRootLogger() {
        return GlassFishLogManager.getLogManager().getRootLogger();
    }


    private GlassFishLogManagerConfiguration getRuntimeConfiguration() throws IOException {
        final Map<String, String> instanceLogCfgMap = getResolvedLoggingProperties();
        final LoggingProperties instanceLogCfg = new LoggingProperties();
        instanceLogCfg.putAll(instanceLogCfgMap);
        return new GlassFishLogManagerConfiguration(instanceLogCfg);
    }


    private Map<String, String> getResolvedLoggingProperties() throws IOException {
        final Map<String, String> properties = getLoggingProperties();
        properties.entrySet().stream().forEach(PROPERTY_VALUE_RESOLVER);
        return properties;
    }


    private void reconfigure(final File configFile) {
        GlassFishLoggingTracer.trace(getClass(), () -> "reconfigure(" + configFile + ")");
        LOG.info(() -> "Using property file: " + configFile);
        if (!GlassFishLogManager.isGlassFishLogManager()) {
            try (FileInputStream configuration = new FileInputStream(configFile)) {
                LogManager.getLogManager().updateConfiguration(configuration, null);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, LogFacade.ERROR_APPLYING_CONF, e);
            }
            return;
        }
        final GlassFishLogManager manager = GlassFishLogManager.getLogManager();
        try {
            final GlassFishLogManagerConfiguration cfg = getRuntimeConfiguration();
            if (cfg == null) {
                GlassFishLoggingTracer.error(getClass(), "Logging configuration is not available!");
                return;
            }
            final ReconfigurationAction reconfig = new ReconfigurationAction(cfg);
            manager.reconfigure(cfg, reconfig, null);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, LogFacade.ERROR_APPLYING_CONF, e);
        }
    }


    private void reconfigureGlassFishLogHandler() {
        final GlassFishLogHandler handler = JULHelperFactory.getHelper().findGlassFishLogHandler();
        if (handler == null) {
            LOG.warning("The GlassFishLogHandler was not found, it's reconfiguration is not possible.");
        } else {
            final GlassFishLogHandlerConfiguration cfg = createGlassFishLogHandlerConfiguration(GlassFishLogHandler.class);
            handler.reconfigure(cfg);
        }
    }


    private boolean isOneOf(final String key, final GlassFishLogHandlerProperty attribute, final Class<?>... handlerClasses) {
        for (Class<?> handlerClass : handlerClasses) {
            if (attribute.getPropertyFullName(handlerClass).equals(key)) {
                return true;
            }
        }
        return false;
    }


    private final class ReconfigurationAction implements Action {

        private final GlassFishLogManagerConfiguration cfg;
        private final ClassLoader classLoader;

        private ReconfigurationAction(final GlassFishLogManagerConfiguration cfg) {
            this.cfg = cfg;
            this.classLoader = Thread.currentThread().getContextClassLoader();
        }


        @Override
        public ClassLoader getClassLoader() {
            return this.classLoader;
        }


        @Override
        public void run() {
            reconfigureGlassFishLogHandler();

            final Map<String, Level> loggerLevels = new HashMap<>();
            final Map<String, Level> handlerLevels = new HashMap<>();
            final Handler[] rootHandlers = getRootHandlers();
            LOG.config(() -> "Actual root handlers=" + Arrays.toString(rootHandlers));
            cfg.toStream().forEach(entry -> {
                if (checkLevels(entry.getKey(), entry.getValue(), handlerLevels, loggerLevels)) {
                    return;
                }
            });

            for (Handler handler : rootHandlers) {
                handler.setLevel(handlerLevels.getOrDefault(handler.getClass().getName(), Level.INFO));
            }
        }

        private boolean checkLevels(final String key, final String value,
            final Map<String, Level> handlerLevels, final Map<String, Level> loggerLevels) {
            if (key.endsWith(".level")) {
                final String name = key.substring(0, key.lastIndexOf(".level"));
                final Level level = Level.parse(value);
                if (isKnownHandlerClass(name)) {
                    handlerLevels.put(name, level);
                } else {
                    loggerLevels.put(name, level);
                }
                return true;
            }
            return false;
        }
    }


    private boolean isKnownHandlerClass(final String name) {
        try {
            Class<?> handlerClazz = Class.forName(name, false, LogManagerService.class.getClassLoader());
            return Handler.class.isAssignableFrom(handlerClazz);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }


    private static final class LoggingCfgFileChangeListener implements FileMonitoring.FileChangeListener {

        private final Consumer<File> action;


        LoggingCfgFileChangeListener(final Consumer<File> action) {
            this.action = action;
        }


        @Override
        public void changed(File changedFile) {
            LOG.info(() -> "Detected change of file: " + changedFile);
            action.accept(changedFile);
        }


        @Override
        public void deleted(File deletedFile) {
            LOG.log(Level.WARNING, LogFacade.CONF_FILE_DELETED, deletedFile.getAbsolutePath());
        }
    }
}
