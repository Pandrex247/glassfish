/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.connectors.service;

import com.sun.appserv.connectors.internal.api.ConnectorRuntimeException;
import com.sun.appserv.connectors.internal.api.PoolingException;
import com.sun.enterprise.config.serverbeans.ResourcePool;
import com.sun.enterprise.connectors.ActiveResourceAdapter;
import com.sun.enterprise.connectors.ConnectorConnectionPool;
import com.sun.enterprise.connectors.ConnectorDescriptorInfo;
import com.sun.enterprise.connectors.ConnectorRegistry;
import com.sun.enterprise.connectors.ConnectorRuntime;
import com.sun.enterprise.connectors.ConnectorRuntimeExtension;
import com.sun.enterprise.connectors.PoolMetaData;
import com.sun.enterprise.connectors.authentication.ConnectorSecurityMap;
import com.sun.enterprise.connectors.authentication.RuntimeSecurityMap;
import com.sun.enterprise.connectors.util.ConnectionDefinitionUtils;
import com.sun.enterprise.connectors.util.ConnectionPoolObjectsUtils;
import com.sun.enterprise.connectors.util.ConnectionPoolReconfigHelper;
import com.sun.enterprise.connectors.util.ResourcesUtil;
import com.sun.enterprise.connectors.util.SecurityMapUtils;
import com.sun.enterprise.connectors.util.SetMethodAction;
import com.sun.enterprise.deployment.ConnectorConfigProperty;
import com.sun.enterprise.deployment.ResourcePrincipalDescriptor;
import com.sun.enterprise.resource.listener.UnpooledConnectionEventListener;
import com.sun.enterprise.resource.pool.PoolManager;
import com.sun.enterprise.util.i18n.StringManager;

import jakarta.resource.ResourceException;
import jakarta.resource.spi.ConnectionRequestInfo;
import jakarta.resource.spi.ManagedConnection;
import jakarta.resource.spi.ManagedConnectionFactory;
import jakarta.resource.spi.TransactionSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import javax.naming.NamingException;
import javax.security.auth.Subject;

import org.glassfish.api.naming.SimpleJndiName;
import org.glassfish.connectors.config.SecurityMap;
import org.glassfish.internal.api.Globals;
import org.glassfish.internal.api.RelativePathResolver;
import org.glassfish.resourcebase.resources.api.PoolInfo;
import org.glassfish.resourcebase.resources.api.ResourceInfo;
import org.jvnet.hk2.config.types.Property;


/**
 * Connector connection pool admin service performs the
 * functionality of creation, deletion, recreation, testing of the pools.
 *
 * @author Srikanth P and Aditya Gore
 */

public class ConnectorConnectionPoolAdminServiceImpl extends ConnectorService {

    private static final StringManager I18N = StringManager.getManager(ConnectorConnectionPoolAdminServiceImpl.class);


    /**
     * Creates connector connection pool in the connector container.
     *
     * @param connectorPoolObj ConnectorConnectionPool instance to be bound to JNDI. This
     *                         object contains the pool properties.
     * @throws ConnectorRuntimeException When creation of pool fails.
     */
    public void createConnectorConnectionPool(ConnectorConnectionPool connectorPoolObj)
        throws ConnectorRuntimeException {

        if (connectorPoolObj == null) {
            throw new ConnectorRuntimeException(I18N.getString("ccp_adm.wrong_params_for_create"));
        }
        PoolInfo poolInfo = connectorPoolObj.getPoolInfo();
        SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
        try {
            _runtime.getResourceNamingService().publishObject(poolInfo, jndiNameForPool, connectorPoolObj, true);
            ManagedConnectionFactory mcf = obtainManagedConnectionFactory(poolInfo);
            if (mcf == null) {
                _runtime.getResourceNamingService().unpublishObject(poolInfo, jndiNameForPool);
                String i18nMsg = I18N.getString("ccp_adm.failed_to_create_mcf", poolInfo);
                ConnectorRuntimeException cre = new ConnectorRuntimeException(i18nMsg);
                _logger.log(Level.SEVERE, "rardeployment.mcf_creation_failure", poolInfo);
                _logger.log(Level.SEVERE, "", cre);
                throw cre;
            }
        } catch (NamingException ex) {

            String i18nMsg = I18N.getString("ccp_adm.failed_to_publish_in_jndi", poolInfo);
            ConnectorRuntimeException cre = new ConnectorRuntimeException(i18nMsg);
            cre.initCause(ex);
            _logger.log(Level.SEVERE, "rardeployment.pool_jndi_bind_failure", poolInfo);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        } catch (NullPointerException ex) {
            try {
                _runtime.getResourceNamingService().unpublishObject(poolInfo, jndiNameForPool);
            } catch (NamingException ne) {
                if(_logger.isLoggable(Level.FINE)) {
                    _logger.log(Level.FINE, "Failed to unbind connection pool object  ", poolInfo);
                }
            }

            String i18nMsg = I18N.getString("ccp_adm.failed_to_register_mcf", poolInfo);
            ConnectorRuntimeException cre = new ConnectorRuntimeException(i18nMsg);
            cre.initCause(ex);
            _logger.log(Level.SEVERE, "rardeployment.mcf_registration_failure", poolInfo);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        }
    }


    /**
     * Deletes connector Connection pool
     *
     * @param poolInfo Name of the pool to delete
     * @throws ConnectorRuntimeException if pool deletion operation fails
     */

    public void deleteConnectorConnectionPool(PoolInfo poolInfo)
            throws ConnectorRuntimeException {
        deleteConnectorConnectionPool(poolInfo, false );
    }

    /**
     * Deletes connector Connection pool. Here check in made whether resources
     * pertaining to pool are present in domain.xml.
     *
     * @param poolInfo Name of the pool to delete
     * @param cascade  If true all the resources associed with that are also
     *                 deleted from connector container
     *                 If false and if some resources pertaining to pool
     *                 are present deletion operation fails . If no resources
     *                 are present pool is deleted.
     * @throws ConnectorRuntimeException if pool deletion operation fails
     */

    public void deleteConnectorConnectionPool(PoolInfo poolInfo, boolean cascade)
            throws ConnectorRuntimeException {

        if (poolInfo == null) {
            _logger.log(Level.WARNING, "ccp_adm.null_pool_name");
            String i18nMsg = I18N.getString("ccp_adm.null_pool_name");
            throw new ConnectorRuntimeException(i18nMsg);
        }

        boolean errorOccured = false;
        killPool(poolInfo);
        boolean result = _registry.removeManagedConnectionFactory(poolInfo);

        if (!result) {
            if(_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "rardeployment.mcf_removal_failure", poolInfo);
            }
            return;
        }

        try {
            SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
            _runtime.getResourceNamingService().unpublishObject(poolInfo, jndiNameForPool);
        } catch (NamingException ne) {
            _logger.log(Level.SEVERE, "rardeployment.connectionpool_removal_from_jndi_error", poolInfo);
            String i18nMsg = I18N.getString("ccp_adm.failed_to_remove_from_jndi", poolInfo);
            ConnectorRuntimeException cre = new ConnectorRuntimeException(i18nMsg);
            cre.initCause(ne);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        }
        if (errorOccured){
            String i18nMsg = I18N.getString("ccp_adm.failed_to_delete_conn_res", poolInfo);
            ConnectorRuntimeException cre = new
                    ConnectorRuntimeException(i18nMsg);
            _logger.log(Level.SEVERE, "rardeployment.all_resources_removal_error", poolInfo);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        }
    }

    /**
     * Kills a specific pool
     *
     * @param poolInfo poolName to kill
     */
    public void killPool(PoolInfo poolInfo) {
        _runtime.getPoolManager().killPool(poolInfo);
    }


    /**
     * Gets the properties of the Java bean connection definition class that
     * have setter methods defined and the default values as provided by the
     * Connection Definition java bean developer.
     *
     * @param connectionDefinitionClassName The Connection Definition Java bean class for which
     *            overrideable properties are required.
     * @return Map<String, Object> String represents property name
     *         and Object is the defaultValue that is a primitive type or String
     */
    public static Map<String, Object> getConnectionDefinitionPropertiesAndDefaults(String connectionDefinitionClassName,
        String resType) {
        return ConnectionDefinitionUtils.getConnectionDefinitionPropertiesAndDefaults(connectionDefinitionClassName,
            resType);
    }

    /**
     * asadmin test-connection-pool
     * This method is used to provide backend functionality for the
     * test-connection-pool asadmin command. Briefly the design is as
     * follows:<br>
     * 1. obtainManagedConnection for the poolname<br>
     * 2. lookup ConnectorDescriptorInfo from InitialContext using poolname<br>
     * 3. from cdi get username and password<br>
     * 4. create ResourcePrincipalDescriptor using default username and password<br>
     * 5. create a Subject from this (doPriveleged)<br>
     * 6. createManagedConnection using above subject<br>
     * 7. getConnection from the ManagedConnection with above subject<br>
     *
     * @param poolInfo connection-pool name
     * @return true if the connection pool is healthy. false otherwise
     * @throws ResourceException if pool is not usable
     */
    public boolean testConnectionPool(PoolInfo poolInfo)
            throws ResourceException {
        //dump(poolName); //TODO V3 no way to call dump ?
        ManagedConnection con = null;
        try {
            //Create the ManagedConnection
            con = (ManagedConnection) getUnpooledConnection(poolInfo, null, false);
        } catch (Exception re) {
            //Since we have an exception, the pool is not good
            Object params[] = new Object[]{poolInfo, re.getMessage()};
            _logger.log(Level.WARNING, "test.connection.pool.failed", params);
            ResourceException e = new ResourceException(re.getLocalizedMessage() +
                    " Please check the server.log for more details.");
            e.initCause(re);
            throw e;
        } finally {
            try {
                //destroy the MC
                if (con != null) {
                    con.destroy();
                }
            } catch (Throwable e) {
                //ignore
            }
        }

        //We did not get a ResourceException, so pool must be OK
        return true;
    }

    /**
     * Utility method that is used to get the default subject for the
     * specified mcf and resource principal.
     * @param poolInfo
     * @param mcf
     * @param prin
     * @return
     * @throws jakarta.resource.ResourceException
     */
    protected Subject getDefaultSubject(PoolInfo poolInfo, ManagedConnectionFactory mcf,
            ResourcePrincipalDescriptor prin) throws ResourceException {
        ResourcePrincipalDescriptor resourcePrincipalDescriptor = null;
        if (prin == null) {
            try {
                resourcePrincipalDescriptor = getDefaultResourcePrincipal(poolInfo, mcf);
            } catch (NamingException ne) {
                _logger.log(Level.WARNING, "jdbc.pool_not_reachable",
                        ne.getMessage());
                String l10nMsg = I18N.getString(
                        "pingpool.name_not_bound", poolInfo);
                ResourceException e = new ResourceException(l10nMsg + poolInfo);
                e.initCause(ne);
                throw e;
            }
        } else {
            resourcePrincipalDescriptor = prin;
        }

        final Subject defaultSubject =
                ConnectionPoolObjectsUtils.createSubject(mcf, resourcePrincipalDescriptor);


        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("using subject: " + defaultSubject);

        }
        return defaultSubject;
    }

    /**
     * Utility method to get Managed connection from the supplied mcf and
     * default subject.
     * @param mcf
     * @param defaultSubject
     * @return
     * @throws jakarta.resource.ResourceException
     */
    protected ManagedConnection getManagedConnection(ManagedConnectionFactory mcf,
            Subject defaultSubject, ConnectionRequestInfo cReqInfo) throws ResourceException {

        ManagedConnection mc = null;

        //Create the ManagedConnection
        mc = mcf.createManagedConnection(defaultSubject, cReqInfo);
        return mc;

    }


    /**
     * This method is used to provide backend functionality for the
     * ping-connection-pool asadmin command. Briefly the design is as
     * follows:<br>
     * 1. obtainManagedConnectionFactory for the poolname<br>
     * 2. lookup ConnectorDescriptorInfo from InitialContext using poolname<br>
     * 3. from cdi get username and password<br>
     * 4. create ResourcePrincipalDescriptor using default username and password<br>
     * 5. create a Subject from this (doPriveleged)<br>
     * 6. createManagedConnection using above subject<br>
     * 7. add a dummy ConnectionEventListener to the mc that simply handles connectionClosed
     * 8. getConnection from the ManagedConnection with above subject<br>
     *
     * @param poolInfo The poolname from whose MCF to obtain the unpooled mc
     * @param principal The ResourcePrincipalDescriptor to use for authenticating the request if not null.
     *            If null, the pool's default authentication mechanism is used
     * @param returnConnectionHandle If true will return the logical connection handle
     *            derived from the Managed Connection, else will only return mc
     * @return an unPooled connection
     * @throws ResourceException for various error conditions
     */
    public Object getUnpooledConnection(PoolInfo poolInfo, ResourcePrincipalDescriptor principal,
        boolean returnConnectionHandle) throws ResourceException {
        ManagedConnectionFactory mcf = null;
        ResourcePool poolToDeploy = null;
        boolean needToUndeployPool = false;
        ConnectorRuntime runtime = ConnectorRuntime.getRuntime();

        try {
            //START CR 6597868
            if (!isPoolReferredByResource(poolInfo)) {
                if (_registry.isMCFCreated(poolInfo)){
                    unloadAndKillPool(poolInfo);
                }
            }
            //END CR 6597868
            mcf = obtainManagedConnectionFactory(poolInfo, new Hashtable<>());
        } catch (ConnectorRuntimeException re) {
            _logger.fine("getUnpooledConnection:: obtainManagedConnectionFactory threw exception."
                + " So doing checkAndLoadPoolResource ...");
            if (checkAndLoadPool(poolInfo)) {
                _logger.fine("getUnpooledConnection:: checkAndLoadPoolResource is true");
                try {

                    //deploy the pool resource if not already done
                    //The pool resource would get loaded in case we are in DAS
                    //due to the checkAndLoadPoolResource call
                    //but in EE, if the pool we are trying to access is in a
                    //remote instance, the pool will not have been created
                    if (!isConnectorConnectionPoolDeployed(poolInfo)) {
                        _logger.fine("getUnpooledConnection :: isConnectorConnectionPoolDeployed is false");
                        try {
                            poolToDeploy = runtime.getResources(poolInfo).getResourceByName(ResourcePool.class,
                                poolInfo.getName());
                            runtime.getResourceDeployer(poolToDeploy).deployResource(poolToDeploy);
                            _logger.log(Level.FINE, "getUnpooledConnection:: force deployed the ConnectionPool: {0}",
                                poolInfo);
                            needToUndeployPool = true;
                        } catch (Exception e) {
                            _logger.log(Level.SEVERE, "jdbc.could_not_do_actual_deploy for : ", poolInfo);
                            throw new ResourceException(e);
                        }
                    }
                    _logger.fine("getUnpooledConnection :: Now calling obtainManagedConnectionFactory again");
                    mcf = obtainManagedConnectionFactory(poolInfo);
                    _logger.fine("getUnpooledConnection:: done obtainManagedConnectionFactory again");

                } catch (ConnectorRuntimeException creAgain) {
                    String l10nMsg = I18N.getString(
                            "pingpool.cannot_obtain_mcf", poolInfo);
                    _logger.log(Level.WARNING, "jdbc.pool_not_reachable", l10nMsg);
                    ResourceException e = new ResourceException(l10nMsg);
                    e.initCause(creAgain);
                    throw e;
                }
            } else {
                _logger.log(Level.WARNING, "jdbc.pool_not_reachable", re.getMessage());
                String l10nMsg = I18N.getString("pingpool.cannot_obtain_mcf", poolInfo);
                ResourceException e = new ResourceException(l10nMsg);
                e.initCause(re);
                throw e;
            }
        }

        ResourcePrincipalDescriptor resourcePrincipalDescriptor = null;
        if (principal == null) {
            try {
                resourcePrincipalDescriptor = getDefaultResourcePrincipal(poolInfo, mcf);
            } catch (NamingException ne) {
                _logger.log(Level.WARNING, "jdbc.pool_not_reachable", ne.getMessage());
                String l10nMsg = I18N.getString("pingpool.name_not_bound", poolInfo);
                ResourceException e = new ResourceException(l10nMsg + poolInfo);
                e.initCause(ne);
                throw e;
            }
        } else {
            resourcePrincipalDescriptor = principal;
        }

        final Subject defaultSubject = ConnectionPoolObjectsUtils.createSubject(mcf, resourcePrincipalDescriptor);

        if (_logger.isLoggable(Level.FINE)) {
            _logger.fine("using subject: " + defaultSubject);
        }

        //Create the ManagedConnection
        ManagedConnection mc = mcf.createManagedConnection(defaultSubject, null);
        //We are done with the pool for now, so undeploy if we deployed
        //it here
        if (needToUndeployPool) {
            if (poolToDeploy != null) {
                _logger.fine("getUnpooledConnection :: need to force undeploy pool");
                try {
                    runtime.getResourceDeployer(poolToDeploy).undeployResource(poolToDeploy);
                } catch (Exception e) {
                    _logger.log(Level.FINE, "getUnpooledConnection: error undeploying pool", e);
                }
                _logger.fine("getUnpooledConnection :: done.. force undeploy of pool");
            }
        }
        //Add our dummy ConnectionEventListener impl.
        //This impl only knows how to handle connectionClosed events
        mc.addConnectionEventListener(new UnpooledConnectionEventListener());
        return returnConnectionHandle ? mc.getConnection(defaultSubject, null) : mc;
    }

    //START CR 6597868
    /** This method is used to find out if pool is linked to data-source or not
      * if pool is not linked then ping will not take changes to pool
      * dynamically.
      * @param poolInfo pool-name
      * @return boolean indicating whether the pool is referred or not
      */
    private boolean isPoolReferredByResource(PoolInfo poolInfo){
        ResourcesUtil resUtil = ResourcesUtil.createInstance();
        boolean isJdbcPoolReferredInServerInstance = false;
        Collection<ConnectorRuntimeExtension> extensions =
                Globals.getDefaultHabitat().getAllServices(ConnectorRuntimeExtension.class);
        for(ConnectorRuntimeExtension extension : extensions) {
            isJdbcPoolReferredInServerInstance = extension.isConnectionPoolReferredInServerInstance(poolInfo);
        }

        return (resUtil.isPoolReferredInServerInstance(poolInfo) || isJdbcPoolReferredInServerInstance);
    }
    //END CR 6597868


    /**
     * Utility method to get property value from ConnectorDescriptorInfo.
     * @param prop
     * @return
     */
    public String getPropertyValue(String prop,
            ConnectorConnectionPool connectorConnectionPool) {
        String result = null;
        ConnectorDescriptorInfo cdi = connectorConnectionPool.getConnectorDescriptorInfo();

        Set mcfConfigProperties = cdi.getMCFConfigProperties();
        Iterator mcfConfPropsIter = mcfConfigProperties.iterator();
        while (mcfConfPropsIter.hasNext()) {
            ConnectorConfigProperty  envProp = (ConnectorConfigProperty ) mcfConfPropsIter.next();

            if (envProp.getName().toUpperCase(Locale.getDefault()).equals(prop)) {
                result = envProp.getValue();
            }
        }
        return result;
    }

    private ResourcePrincipalDescriptor getDefaultResourcePrincipal( PoolInfo poolInfo,
        ManagedConnectionFactory mcf ) throws NamingException {
        return getDefaultResourcePrincipal(poolInfo, mcf, null);
    }


    /**
     * Returns a ResourcePrincipalDescriptor object populated with a pool's
     * default USERNAME and PASSWORD
     *
     * @throws NamingException if poolname lookup fails
     */
    private ResourcePrincipalDescriptor getDefaultResourcePrincipal(PoolInfo poolInfo, ManagedConnectionFactory mcf,
        Hashtable env) throws NamingException {
        String userName = null;
        String password = null;
        // All this to get the default user name and principal
        ConnectorConnectionPool connectorConnectionPool = null;
        try {
            SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
            connectorConnectionPool = _runtime.getResourceNamingService().lookup(poolInfo, jndiNameForPool, env);
        } catch (NamingException ne) {
            throw ne;
        }

        userName = getPropertyValue("USERNAME", connectorConnectionPool);
        if(userName == null) {
            userName = getPropertyValue("USER", connectorConnectionPool);
        }
        password = getPropertyValue("PASSWORD", connectorConnectionPool);
        try {
            password = RelativePathResolver.getRealPasswordFromAlias(password);
        } catch(Exception e) {
            _logger.log(Level.WARNING, "unable_to_get_password_from_alias", e);
        }

        // To avoid using "", "" as the default username password, try to get
        // the username and password from MCF, to use in subject. MQ adapter
        // cannot use "","" as the username/password.

        if (userName == null || userName.isBlank()) {
            userName = ConnectionPoolObjectsUtils.getValueFromMCF("User", poolInfo, mcf);
            password = ConnectionPoolObjectsUtils.getValueFromMCF("Password", poolInfo, mcf);
        }
        return new ResourcePrincipalDescriptor(userName, password);
    }

    /**
     * Rebinds the connection pool with matchning flag set.
     *
     * @param poolInfo pool for which matching need to be switched on
     * @throws ConnectorRuntimeException , if a Naming error occurs.
     */
    public void switchOnMatching(PoolInfo poolInfo) throws ConnectorRuntimeException {
        try {
            ConnectorConnectionPool origCcp = getOriginalConnectorConnectionPool(poolInfo);
            origCcp.setMatchConnections(true);

            // now rebind the object in jndi
            SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
            _runtime.getResourceNamingService().unpublishObject(poolInfo, jndiNameForPool);
            _runtime.getResourceNamingService().publishObject(poolInfo, jndiNameForPool, origCcp, true);
        } catch (NamingException e) {
            ConnectorRuntimeException ex = new ConnectorRuntimeException(e.getMessage());
            throw (ConnectorRuntimeException) ex.initCause(e);
        }
    }

   /**
    * Create a ConnectorConnectionPool from information in memory
    */
   private ConnectorConnectionPool getOriginalConnectorConnectionPool(PoolInfo poolInfo) throws NamingException {
       SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
       return _runtime.getResourceNamingService().lookup(poolInfo, jndiNameForPool);
    }

    private ConnectorConnectionPool getConnectorConnectionPool(PoolInfo poolInfo)
        throws ConnectorRuntimeException, NamingException  {
        return getConnectorConnectionPool(poolInfo, null);
    }


    /**
     * Returns the connector connection pool object corresponding
     * to the pool name
     *
     * @param poolInfo Name of the pool.MCF pertaining to this pool is created/returned.
     * @return Connector connection pool corresponding to this instance
     * @throws ConnectorRuntimeException if creation/retrieval of MCF fails
     */
    private ConnectorConnectionPool getConnectorConnectionPool(PoolInfo poolInfo, Hashtable env)
        throws ConnectorRuntimeException, NamingException {
        SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
        ConnectorConnectionPool connectorConnectionPool = _runtime.getResourceNamingService().lookup(poolInfo,
            jndiNameForPool, env);
        if (connectorConnectionPool == null) {
            String i18nMsg = I18N.getString("ccp_adm.null_pool", poolInfo);
            ConnectorRuntimeException cre = new ConnectorRuntimeException(i18nMsg);
            _logger.log(Level.SEVERE, "rardeployment.connectionpool_object_null", poolInfo);
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "", cre);
            }
            throw cre;
        }
        return connectorConnectionPool;
    }


    /**
     * Returns the resource adapter object corresponding
     * to the pool
     *
     * @param connectorConnectionPool Name of the pool. MCF pertaining to this pool is created/returned.
     * @return Resource adapter instance corresponding to this pool.
     * @throws ConnectorRuntimeException if creation/retrieval of RA fails
     */
    private ActiveResourceAdapter getResourceAdapter(ConnectorConnectionPool connectorConnectionPool)
        throws ConnectorRuntimeException {
        String rarName = connectorConnectionPool.getConnectorDescriptorInfo().getRarName();
        ActiveResourceAdapter activeResourceAdapter = getActiveResourceAdapter(rarName);
        if (activeResourceAdapter == null) {
            String i18nMsg = I18N.getString("ccp_adm.active_ra_not_init", rarName);
            ConnectorRuntimeException cre = new ConnectorRuntimeException(i18nMsg);
            _logger.log(Level.SEVERE, "rardeployment.resourceadapter_not_initialized", rarName);
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "", cre);
            }
            throw cre;
        }
        return activeResourceAdapter;
    }


    private ActiveResourceAdapter getActiveResourceAdapter(String rarName) throws ConnectorRuntimeException {
        ActiveResourceAdapter activeResourceAdapter = _registry.getActiveResourceAdapter(rarName);
        // we dont need this check for normal resources of domain.xml config, but
        // datasource-definition needs it.
        if (activeResourceAdapter == null) {
            ifSystemRarLoad(rarName);
            activeResourceAdapter = _registry.getActiveResourceAdapter(rarName);
        }
        return activeResourceAdapter;
    }


    /**
     * Returns the MCF instances.
     *
     * @param poolInfo Name of the pool.MCF pertaining to this pool is
     *            created/returned.
     * @return created/already present MCF instance
     * @throws ConnectorRuntimeException if creation/retrieval of MCF fails
     */
    public ManagedConnectionFactory[] obtainManagedConnectionFactories(PoolInfo poolInfo)
        throws ConnectorRuntimeException {
        ManagedConnectionFactory[] mcfs = null;
        String raName = null;
        try {
            ConnectorConnectionPool conPool = getConnectorConnectionPool(poolInfo);
            ActiveResourceAdapter activeResourceAdapter = getResourceAdapter(conPool);
            raName = activeResourceAdapter.getModuleName();
            mcfs = activeResourceAdapter.createManagedConnectionFactories(conPool, null);
        } catch (NamingException ne) {
            String i18nMsg = I18N.getString("pingpool.name_not_bound", poolInfo);
            throw new ConnectorRuntimeException(i18nMsg, ne);
        } catch (NullPointerException ne) {
            String i18nMsg = I18N.getString("ccp_adm.failed_to_register_mcf", poolInfo);
            throw new ConnectorRuntimeException(i18nMsg, ne);
        }
        for (ManagedConnectionFactory mcf : mcfs) {
            validateMCF(mcf, raName);
        }
        return mcfs;
    }

    private void validateMCF(ManagedConnectionFactory mcf, String raName) {
        _runtime.getConnectorBeanValidator().validateJavaBean(mcf, raName);
    }


    public ManagedConnectionFactory obtainManagedConnectionFactory(PoolInfo poolInfo) throws ConnectorRuntimeException{
        return obtainManagedConnectionFactory(poolInfo, null);
    }

    /**
     * Returns the MCF instance. If the MCF is already created and
     * present in connectorRegistry that instance is returned. Otherwise it
     * is created explicitly and added to ConnectorRegistry.
     *
     * @param poolInfo Name of the pool.MCF pertaining to this pool is
     *                 created/returned.
     * @return created/already present MCF instance
     * @throws ConnectorRuntimeException if creation/retrieval of MCF fails
     */
    public ManagedConnectionFactory obtainManagedConnectionFactory(PoolInfo poolInfo, Hashtable env)
            throws ConnectorRuntimeException {
        _logger.log(Level.FINE, "obtainManagedConnectionFactory(poolInfo={0}, env)", poolInfo);
        try {
            if (_registry.isMCFCreated(poolInfo)) {
                return _registry.getManagedConnectionFactory(poolInfo);
            }
            ConnectorConnectionPool connectorConnectionPool = getConnectorConnectionPool(poolInfo, env);
            ActiveResourceAdapter activeResourceAdapter = getResourceAdapter(connectorConnectionPool);
            ClassLoader loader = activeResourceAdapter.getClassLoader();
            ManagedConnectionFactory mcf
                = activeResourceAdapter.createManagedConnectionFactory(connectorConnectionPool, loader);
            if (mcf != null) {
                // validate MCF before it is used or related pooling infrastructure is created.
                validateMCF(mcf, activeResourceAdapter.getModuleName());

                ResourcePrincipalDescriptor prin = getDefaultResourcePrincipal(poolInfo, mcf, env);
                Subject s = ConnectionPoolObjectsUtils.createSubject(mcf, prin);
                int txSupport = connectorConnectionPool.getTransactionSupport();

                // JSR-322 : check the runtime transaction level support of MCF and use
                // appropriately.
                if (mcf instanceof jakarta.resource.spi.TransactionSupport) {
                    TransactionSupport.TransactionSupportLevel mcfTS = ((jakarta.resource.spi.TransactionSupport) mcf)
                        .getTransactionSupport();

                    int containerTxSupport = ConnectionPoolObjectsUtils.convertSpecTxSupportToContainerTxSupport(mcfTS);
                    boolean isValidTxSupportLevel = ConnectionPoolObjectsUtils
                        .isTxSupportConfigurationSane(containerTxSupport, activeResourceAdapter.getModuleName());

                    if (isValidTxSupportLevel) {
                        txSupport = containerTxSupport;
                    } else {
                        Object[] params = {mcfTS, activeResourceAdapter.getModuleName()};
                        String i18nMsg = I18N.getString("ccp_adm_service.incorrect_tx_support", params);
                        ConnectorRuntimeException cre = new ConnectorRuntimeException(i18nMsg);
                        _logger.log(Level.SEVERE, "rardeployment.incorrect_tx_support",
                            connectorConnectionPool.getName());
                        throw cre;
                    }
                }

                boolean isPM = connectorConnectionPool.isNonComponent();
                boolean isNonTx = connectorConnectionPool.isNonTransactional();
                ConnectorSecurityMap[] securityMaps =
                        connectorConnectionPool.getSecurityMaps();
                RuntimeSecurityMap runtimeSecurityMap =
                        SecurityMapUtils.processSecurityMaps(securityMaps);
                boolean lazyEnlistable = connectorConnectionPool.isLazyConnectionEnlist();
                boolean lazyAssoc = connectorConnectionPool.isLazyConnectionAssoc();

                if (isPM || isNonTx) {
                    /*
                    We should not do lazyEnlistment if we are an __pm
                    resource since we won't have an InvocationContext and
                    the lazy enlistment depends upon an InvocationContext
                    For a nonTx resource enlistment (lazy or otherwise)
                    doesn't come into the picture at all
                    */
                    lazyEnlistable = false;
                }

                if (isPM) {
                    //We need to switch off lazy association here because of
                    //the way our Persistence layer behaves. Adding a system
                    //property here to allow other persistence layers to use
                    //lazy association with PM resources
                    if (lazyAssoc) {
                        String str = System.getProperty(
                                "com.sun.enterprise.resource.AllowLazyAssociationWithPM", "FALSE");
                        if (str.toUpperCase(Locale.getDefault()).trim().equals("FALSE")) {
                            lazyAssoc = false;
                        }
                    }
                }

                PoolMetaData pmd = new PoolMetaData(poolInfo, mcf, s, txSupport, prin,
                        isPM, isNonTx, lazyEnlistable, runtimeSecurityMap, lazyAssoc);
                _logger.log(Level.FINE, "Adding metadata: {0}", pmd);
                _registry.addManagedConnectionFactory(poolInfo, pmd);
            }

            PoolType pt = getPoolType(connectorConnectionPool);

            createAndAddPool(poolInfo, pt, env);
            return mcf;
        } catch (NamingException ne) {
            String i18nMsg = I18N.getString("pingpool.name_not_bound", poolInfo);
            throw new ConnectorRuntimeException(i18nMsg, ne);
        } catch (NullPointerException ne) {
            String i18nMsg = I18N.getString("ccp_adm.failed_to_register_mcf", poolInfo);
            throw new ConnectorRuntimeException(i18nMsg, ne);
        }
    }


    private PoolType getPoolType(ConnectorConnectionPool connectorConnectionPool) {
        PoolType pt = PoolType.STANDARD_POOL;
        if (!connectorConnectionPool.isPoolingOn()) {
            pt = PoolType.POOLING_DISABLED;
        } else if (connectorConnectionPool.isAssociateWithThread()) {
            pt = PoolType.ASSOCIATE_WITH_THREAD_POOL;
        } else if (connectorConnectionPool.isPartitionedPool()) {
            pt = PoolType.PARTITIONED_POOL;
        }
        return pt;
    }

    public PoolType getPoolType(PoolInfo poolInfo) throws ConnectorRuntimeException {
        ConnectorConnectionPool ccp;
        try {
            ccp = getConnectorConnectionPool(poolInfo);
        } catch (NamingException e) {
            ConnectorRuntimeException cre = new ConnectorRuntimeException(e.getMessage());
            cre.initCause(e);
            throw cre;
        }
        return getPoolType(ccp);
    }


    /**
     * create an empty connection pool
     *
     * @param poolInfo pool to be created
     * @param pt       type of pool
     * @throws ConnectorRuntimeException
     */
    private void createAndAddPool(PoolInfo poolInfo, PoolType pt, Hashtable env) throws ConnectorRuntimeException {
        _logger.log(Level.FINE, "createAndAddPool(poolInfo={0}, pt, env)", poolInfo);
        PoolManager poolMgr = _runtime.getPoolManager();
        try {
            poolMgr.createEmptyConnectionPool(poolInfo, pt, env);
        } catch (PoolingException pe) {
            String i18nMsg = I18N.getString("ccp_adm.failed_to_create_pool_object");
            ConnectorRuntimeException cre = new ConnectorRuntimeException(i18nMsg);
            cre.initCause(pe);
            throw cre;
        }
    }

    /**
     * Checks if a conncetor connection pool has been deployed to this server instance
     *
     * @param poolInfo
     * @return true if found
     */
    public boolean isConnectorConnectionPoolDeployed(PoolInfo poolInfo) {
        try {
            SimpleJndiName jndiName = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
            _runtime.getResourceNamingService().lookup(poolInfo, jndiName);
            return true;
        } catch (NamingException e) {
            return false;
        }
    }

    /**
     * Reconfigure a connection pool.
     * This method compares the passed connector connection pool with the one
     * in memory. If the pools are unequal and the MCF properties are changed
     * a pool recreate is required. However if the pools are unequal and the
     * MCF properties are not changed a recreate is not required
     *
     * @param ccp           - the Updated connector connection pool object that admin
     *                      hands over
     * @param excludedProps - A set of excluded property names that we want
     *                      to be excluded in the comparison check while
     *                      comparing MCF properties
     * @return true - if a pool restart is required, false otherwise
     * @throws ConnectorRuntimeException
     */
    public boolean reconfigureConnectorConnectionPool(ConnectorConnectionPool ccp, Set excludedProps)
        throws ConnectorRuntimeException {
        if (ccp == null) {
            throw new ConnectorRuntimeException("No pool to reconfigure, new pool object is null");
        }
        _logger.log(Level.FINE, "new ccp :\n{0}", ccp);

        //see if the new ConnectorConnectionPool is different from
        //the original one and update relevant properties
        PoolInfo poolInfo = ccp.getPoolInfo();
        ConnectorConnectionPool origCcp = null;
        try {
            origCcp = getOriginalConnectorConnectionPool(poolInfo);
        } catch (NamingException ne) {
            throw new ConnectorRuntimeException(ne.getMessage());
        }

        if (origCcp == null) {
            throw new ConnectorRuntimeException("No pool to reconfigure, original pool object is null");
        }
        _logger.log(Level.FINE, "original ccp :\n{0}", origCcp);

        ConnectionPoolReconfigHelper.ReconfigAction action = ConnectionPoolReconfigHelper.compare(origCcp, ccp,
                excludedProps);
        _logger.log(Level.FINE, "pool reconfig action == {0}", action);
        if (action == ConnectionPoolReconfigHelper.ReconfigAction.UPDATE_MCF_AND_ATTRIBUTES) {
            updateMCFAndPoolAttributes(ccp);
        } else if (action == ConnectionPoolReconfigHelper.ReconfigAction.RECREATE_POOL) {
            return true;
        }

        return false;
    }

    private void updateMCFAndPoolAttributes(ConnectorConnectionPool
            ccp) throws ConnectorRuntimeException {
        PoolInfo poolInfo = ccp.getPoolInfo();
        try {
            ConnectorConnectionPool origCcp =
                    getOriginalConnectorConnectionPool(poolInfo);

            //update properties
            origCcp.setSteadyPoolSize(ccp.getSteadyPoolSize());
            origCcp.setMaxPoolSize(ccp.getMaxPoolSize());
            origCcp.setMaxWaitTimeInMillis(ccp.getMaxWaitTimeInMillis());
            origCcp.setPoolResizeQuantity(ccp.getPoolResizeQuantity());
            origCcp.setIdleTimeoutInSeconds(ccp.getIdleTimeoutInSeconds());
            origCcp.setFailAllConnections(ccp.isFailAllConnections());

            //lazyEnlist, lazyAssoc and assocWithThread not required since they result
            //in a pool restart anyways, so they wouldn't have changed if we
            //came here
            origCcp.setMatchConnections(ccp.matchConnections());
            origCcp.setMaxConnectionUsage(ccp.getMaxConnectionUsage());
            origCcp.setNonComponent(ccp.isNonComponent());
            origCcp.setNonTransactional(ccp.isNonTransactional());
            origCcp.setConCreationRetryAttempts(ccp.getConCreationRetryAttempts());
            origCcp.setConCreationRetryInterval
                    (ccp.getConCreationRetryInterval());
            origCcp.setValidateAtmostOncePeriod(ccp.getValidateAtmostOncePeriod());
            origCcp.setConnectionLeakTracingTimeout(ccp.getConnectionLeakTracingTimeout());
            origCcp.setConnectionReclaim(ccp.isConnectionReclaim());

            //now rebind the object in jndi
            SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
            _runtime.getResourceNamingService().unpublishObject(poolInfo, jndiNameForPool);
            _runtime.getResourceNamingService().publishObject(poolInfo, jndiNameForPool, origCcp, true);


        } catch (NamingException ne) {
            throw new ConnectorRuntimeException(ne.getMessage());
        }

        //Check if this pool has been brought into memory
        //If its already in memory, just call reconfig on it

        PoolManager poolMgr = _runtime.getPoolManager();
        try {
            poolMgr.reconfigPoolProperties(ccp);
        } catch (PoolingException pe) {
            throw new ConnectorRuntimeException(pe.getMessage());
        }
        //Run setXXX methods on the copy of the MCF that we have
        //this is done to update the MCF to reflect changes in the
        //MCF properties for which we don't really need to recreate
        // the pool
        ConnectorRegistry registry = ConnectorRegistry.getInstance();
        ManagedConnectionFactory mcf = registry.getManagedConnectionFactory(poolInfo);
        SetMethodAction<ConnectorConfigProperty> sma = new SetMethodAction<>(mcf,
            ccp.getConnectorDescriptorInfo().getMCFConfigProperties());
        try {
            sma.run();
        } catch (Exception e) {
            _logger.log(Level.WARNING, e.getMessage());
            ConnectorRuntimeException cre = new ConnectorRuntimeException(e.getMessage());
            cre.initCause(e);
            throw cre;
        }

        //update the properties "allow-non-component-callers" and
        //"non-transactional-connections" in the PoolMetaData
        PoolMetaData pmd = registry.getPoolMetaData(poolInfo);
        pmd.setIsPM(ccp.isNonComponent());
        pmd.setIsNonTx(ccp.isNonTransactional());
        pmd.setAuthCredentialsDefinedInPool(ccp.getAuthCredentialsDefinedInPool());

        _logger.fine("Pool properties reconfiguration done");
    }


    /**
     * Recreate a connector connection pool. This method essentially does
     * the following things:
     * 1. Delete the said connector connection pool<br>
     * 2. Bind the pool to JNDI<br>
     * 3. Create an MCF for this pool and register with the connector registry<br>
     *
     * @param ccp - the ConnectorConnectionPool to publish
     */
    public void recreateConnectorConnectionPool(ConnectorConnectionPool ccp) throws ConnectorRuntimeException {
        ConnectorRegistry registry = ConnectorRegistry.getInstance();
        if (registry == null) {
            throw new ConnectorRuntimeException("Cannot get ConnectorRegistry");
        }
        PoolInfo poolInfo = ccp.getPoolInfo();
        //First remove this pool from memory
        try {
            unloadAndKillPool(poolInfo);
        } catch (ConnectorRuntimeException cre) {
            throw cre;
        }

        SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
        ManagedConnectionFactory mcf = null;
        try {
            _runtime.getResourceNamingService().publishObject(poolInfo, jndiNameForPool, ccp, true);
            mcf = obtainManagedConnectionFactory(poolInfo);
        } catch (NamingException ne) {
            _logger.log(Level.SEVERE, "rardeployment.pool_jndi_bind_failure", poolInfo);
            String i18nMsg = I18N.getString("ccp_adm.could_not_recreate_pool", poolInfo);
            ConnectorRuntimeException crex = new ConnectorRuntimeException(i18nMsg);
            crex.initCause(ne);
            throw crex;
        } finally {
            if (mcf == null) {
                try {
                    _runtime.getResourceNamingService().unpublishObject(poolInfo, jndiNameForPool);
                } catch (NamingException e) {
                    _logger.log(Level.WARNING, "Unable to unbind the pool configuration object " + "of pool [ "
                        + poolInfo + " ] during MCF creation failure");
                }
                _logger.log(Level.WARNING, "rardeployment.mcf_creation_failure", poolInfo);

                String i18nMsg = I18N.getString("ccp_adm.failed_to_create_mcf", poolInfo);
                throw new ConnectorRuntimeException(i18nMsg);
            }
        }

    }

    /**
     * unloads and kills the connector Connection pool without checking for
     * resources in domain.xml.
     *
     * @param poolInfo Name of the pool to delete
     * @throws ConnectorRuntimeException if pool unload or kill operation fails
     */

    private void unloadAndKillPool(PoolInfo poolInfo)
            throws ConnectorRuntimeException {

        killPool(poolInfo);
        boolean result = _registry.removeManagedConnectionFactory(poolInfo);
        if (!result) {
            _logger.log(Level.SEVERE,
                    "rardeployment.mcf_removal_failure", poolInfo);
            String i18nMsg = I18N.getString(
                    "ccp_adm.wrong_params_for_create", poolInfo);
            ConnectorRuntimeException cre = new
                    ConnectorRuntimeException(i18nMsg);
            if(_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "", cre);
            }
            throw cre;
        }
        try {
            SimpleJndiName jndiNameForPool = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForPool(poolInfo);
            _runtime.getResourceNamingService().unpublishObject(poolInfo, jndiNameForPool);
        } catch (NamingException ne) {
            String i18nMsg = I18N.getString(
                    "ccp_adm.failed_to_remove_from_jndi", poolInfo);
            ConnectorRuntimeException cre = new
                    ConnectorRuntimeException(i18nMsg);
            cre.initCause(ne);
            _logger.log(Level.SEVERE,
                    "rardeployment.connectionpool_removal_from_jndi_error",
                    poolInfo);
            if(_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "", cre);
            }
            throw cre;
        }

    }


    /**
     * Creates connector connection pool in the connector container.
     *
     * @param ccp ConnectorConnectionPool instance to be bound to JNDI. This
     *            object contains the pool properties.
     * @param connectionDefinitionName Connection definition name against which
     *            connection pool is being created
     * @param rarName Name of the resource adapter
     * @param props Properties of MCF which are present in domain.xml
     *            These properties override the ones present in ra.xml
     * @param securityMaps Array fo security maps.
     * @throws ConnectorRuntimeException When creation of pool fails.
     */

    public void createConnectorConnectionPool(ConnectorConnectionPool ccp, String connectionDefinitionName,
        String rarName, List<Property> props, List<SecurityMap> securityMaps) throws ConnectorRuntimeException {
        if (ccp == null || connectionDefinitionName == null || rarName == null) {
            _logger.log(Level.FINE, "Wrong parameters for pool creation ");
            String i18nMsg = I18N.getString("ccp_adm.wrong_params_for_create");
            throw new ConnectorRuntimeException(i18nMsg);
        }

        createConnectorConnectionPool(ccp);
    }

    /**
     * Flush Connection pool by reinitializing the connections
     * established in the pool.
     * @param poolInfo
     * @throws com.sun.appserv.connectors.internal.api.ConnectorRuntimeException
     */
    public boolean flushConnectionPool(PoolInfo poolInfo) throws ConnectorRuntimeException {
        PoolManager poolMgr = _runtime.getPoolManager();
        try {
            return poolMgr.flushConnectionPool(poolInfo);
        } catch (PoolingException ex) {
            ConnectorRuntimeException e = new ConnectorRuntimeException(
                ex.getLocalizedMessage() + ". Please check the server.log for more details.");
            e.initCause(ex);
            throw e;

        }
    }

    /**
     * Get a sql connection from the DataSource specified by the jdbcJndiName.
     * This API is intended to be used in the DAS. The motivation for having this
     * API is to provide the CMP backend a means of acquiring a connection during
     * the codegen phase. If a user is trying to deploy an app on a remote server,
     * without this API, a resource reference has to be present both in the DAS
     * and the server instance. This makes the deployment more complex for the
     * user since a resource needs to be forcibly created in the DAS Too.
     * This API will mitigate this need.
     *
     * @param resourceInfo the jndi name of the resource being used to get Connection from
     *                 This resource can either be a pmf resource or a jdbc resource
     * @param user     the user used to authenticate this request
     * @param password the password used to authenticate this request
     * @return a java.sql.Connection
     * @throws java.sql.SQLException in case of errors
     */
    public Connection getConnection(ResourceInfo resourceInfo, String user, String password)
            throws SQLException {
        java.sql.Connection con = null;
        try {
            //DASResourcesUtil.setAdminConfigContext();
            PoolInfo poolInfo = getPoolNameFromResourceJndiName(resourceInfo);
            if(poolInfo == null){
                throw new SQLException("No pool by name exists ");
            }
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine("ConnectorRuntime.getConnection :: poolName : " + poolInfo);
            }
            //Maintain consitency with the ConnectionManagerImpl change to be checked in later
            String passwd = (password == null) ? "" : password;

            //From what we have seen so far, the user cannot be null
            //but password can be
            //if user is null we will use default authentication
            //TODO: Discuss if this is the right thing to do
            ResourcePrincipalDescriptor prin = (user == null) ?
                    null : new ResourcePrincipalDescriptor(user, passwd);
            con = (java.sql.Connection) getUnpooledConnection(poolInfo, prin, true);
            if (con == null) {
                String i18nMsg = I18N.getString(
                        "ccp_adm.null_unpooled_connection");
                throw new SQLException(i18nMsg);
            }
        } catch (ResourceException re) {
            SQLException sqle = new SQLException(re.getMessage());
            sqle.initCause(re);
            _logger.log(Level.WARNING, "jdbc.exc_get_conn", re.getMessage());
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine(" getConnection in ConnectorRuntime failed : " + re);
            }
            throw sqle;
        } catch (Exception ex) {
            SQLException sqle = new SQLException(ex.getMessage());
            sqle.initCause(ex);
            _logger.log(Level.WARNING, "jdbc.exc_get_conn", ex.getMessage());
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine(" getConnection in ConnectorRuntime failed : " + ex);
            }
            throw sqle;
        }
        return con;
    }

    /**
     * Get a sql connection from the DataSource specified by the jdbcJndiName.
     * This API is intended to be used in the DAS. The motivation for having this
     * API is to provide the CMP backend a means of acquiring a connection during
     * the codegen phase. If a user is trying to deploy an app on a remote server,
     * without this API, a resource reference has to be present both in the DAS
     * and the server instance. This makes the deployment more complex for the
     * user since a resource needs to be forcibly created in the DAS Too.
     * This API will mitigate this need.
     *
     * @param resourceInfo the jndi name of the resource being used to get Connection from
     *                 This resource can either be a pmf resource or a jdbc resource
     * @return a java.sql.Connection
     * @throws java.sql.SQLException in case of errors
     */
    public Connection getConnection(ResourceInfo resourceInfo)
            throws SQLException {
        java.sql.Connection con = null;
        try {
            //DASResourcesUtil.setAdminConfigContext();
            PoolInfo poolInfo = getPoolNameFromResourceJndiName(resourceInfo);
            if(poolInfo == null){
                throw new SQLException("No pool by name exists ");
            }
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine("ConnectorRuntime.getConnection :: poolName : "
                        + poolInfo);
            }
            con = (java.sql.Connection) getUnpooledConnection(poolInfo, null,
                    true);
            if (con == null) {
                String i18nMsg = I18N.getString(
                        "ccp_adm.null_unpooled_connection");
                throw new SQLException(i18nMsg);
            }
        } catch (ResourceException re) {
            SQLException sqle = new SQLException(re.getMessage());
            sqle.initCause(re);
            _logger.log(Level.WARNING, "jdbc.exc_get_conn", re.getMessage());
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine("Exception : " + re);
            }
            throw sqle;
        } catch (Exception ex) {
            SQLException sqle = new SQLException(ex.getMessage());
            sqle.initCause(ex);
            _logger.log(Level.WARNING, "jdbc.exc_get_conn", ex.getMessage());
            if (_logger.isLoggable(Level.FINE)) {
                _logger.fine(" getConnection in ConnectorRuntime failed : " + ex);
            }
            throw sqle;
        }
        return con;
    }

    /**
     * Gets the Pool name that this JDBC resource points to. In case of a PMF resource
     * gets the pool name of the pool pointed to by jdbc resource being pointed to by
     * the PMF resource
     *
     * @param jndiName the jndi name of the resource being used to get Connection from
     *                 This resource can either be a pmf resource or a jdbc resource
     * @return poolName of the pool that this resource directly/indirectly points to
     */
    private PoolInfo getPoolNameFromResourceJndiName(ResourceInfo resourceInfo) {
        PoolInfo poolInfo = null;
        Collection<ConnectorRuntimeExtension> extensions = Globals.getDefaultHabitat()
            .getAllServices(ConnectorRuntimeExtension.class);
        for (ConnectorRuntimeExtension extension : extensions) {
            poolInfo = extension.getPoolNameFromResourceJndiName(resourceInfo);
        }
        return poolInfo;
    }
}
