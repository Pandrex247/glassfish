/*
 * Copyright (c) 2022 Contributors to Eclipse Foundation. All rights reserved.
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.deployment.annotation.handlers;

import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.MethodDescriptor;
import com.sun.enterprise.deployment.MethodPermission;
import com.sun.enterprise.deployment.annotation.context.EjbContext;
import com.sun.enterprise.deployment.annotation.context.WebBundleContext;
import com.sun.enterprise.deployment.annotation.context.WebComponentContext;
import com.sun.enterprise.deployment.util.DOLUtils;
import com.sun.enterprise.deployment.util.TypeUtil;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.apf.AnnotationInfo;
import org.glassfish.apf.AnnotationProcessorException;
import org.glassfish.apf.HandlerProcessingResult;

/**
 * This is an abstract class encapsulate generic behaviour of auth annotations,
 * such as @DenyAll, @PermitAll and @RolesAllowed.
 *
 * @author Shing Wai Chan
 */
abstract class AbstractAuthAnnotationHandler extends AbstractCommonAttributeHandler
    implements PostProcessor<EjbContext> {
    private static final Logger LOG = DOLUtils.getDefaultLogger();

    /**
     * This method processes the EJB Security for the given Annotation.
     */
    protected abstract void processEjbMethodSecurity(Annotation authAnnotation, MethodDescriptor md, EjbDescriptor ejbDesc);


    /**
     * Process Annotation with given EjbContexts.
     *
     * @param ainfo
     * @param ejbContexts
     * @return HandlerProcessingResult
     */
    @Override
    protected HandlerProcessingResult processAnnotation(AnnotationInfo ainfo, EjbContext[] ejbContexts)
        throws AnnotationProcessorException {
        if (!validateAccessControlAnnotations(ainfo)) {
            return getDefaultFailedResult();
        }

        Annotation authAnnotation = ainfo.getAnnotation();
        for (EjbContext ejbContext : ejbContexts) {
            EjbDescriptor ejbDesc = ejbContext.getDescriptor();

            if (ElementType.TYPE.equals(ainfo.getElementType())) {
                // postpone the processing at the end
                ejbContext.addPostProcessInfo(ainfo, this);
            } else {
                Method annMethod = (Method) ainfo.getAnnotatedElement();
                for (MethodDescriptor md : ejbDesc.getSecurityBusinessMethodDescriptors()) {
                    // override by xml
                    if (!hasMethodPermissionsFromDD(md, ejbDesc)) {
                        Method m = md.getMethod(ejbDesc);
                        if (TypeUtil.sameMethodSignature(m, annMethod)) {
                            processEjbMethodSecurity(authAnnotation, md, ejbDesc);
                        }
                    }
                }
            }
        }

        return getDefaultProcessedResult();
    }


    /**
     * Process Annotation with given WebCompContexts.
     *
     * @param ainfo
     * @param webCompContexts
     * @return HandlerProcessingResult
     */
    @Override
    protected HandlerProcessingResult processAnnotation(AnnotationInfo ainfo, WebComponentContext[] webCompContexts)
        throws AnnotationProcessorException {
        // this is not for web component
        return getInvalidAnnotatedElementHandlerResult(webCompContexts[0], ainfo);
    }


    /**
     * Process Annotation with given WebBundleContext.
     *
     * @param ainfo
     * @param webBundleContext
     * @return HandlerProcessingResult
     */
    @Override
    protected HandlerProcessingResult processAnnotation(AnnotationInfo ainfo, WebBundleContext webBundleContext)
        throws AnnotationProcessorException {
        // this is not for web bundle
        return getInvalidAnnotatedElementHandlerResult(webBundleContext, ainfo);
    }


    /**
     * This method is for processing security annotation associated to ejb.
     * Dervied class call this method may like to override
     * protected void processEjbMethodSecurity(Annotation authAnnotation,
     * MethodDescriptor md, EjbDescriptor ejbDesc)
     */
    @Override
    public void postProcessAnnotation(AnnotationInfo ainfo, EjbContext ejbContext) throws AnnotationProcessorException {
        EjbDescriptor ejbDesc = ejbContext.getDescriptor();
        Annotation authAnnotation = ainfo.getAnnotation();

        if (ejbContext.isInherited()
            || (ejbDesc.getMethodPermissionsFromDD() != null && !ejbDesc.getMethodPermissionsFromDD().isEmpty())) {
            Class<?> classAn = (Class<?>) ainfo.getAnnotatedElement();
            for (MethodDescriptor md : ejbDesc.getSecurityBusinessMethodDescriptors()) {
                // override by existing info
                if (classAn.equals(ejbContext.getDeclaringClass(md)) && !hasMethodPermissionsFromDD(md, ejbDesc)) {
                    processEjbMethodSecurity(authAnnotation, md, ejbDesc);
                }
            }
        } else {
            for (MethodDescriptor md : getMethodAllDescriptors(ejbDesc)) {
                processEjbMethodSecurity(authAnnotation, md, ejbDesc);
            }
        }
    }


    /**
     * @return true
     */
    @Override
    protected boolean supportTypeInheritance() {
        return true;
    }


    /**
     * This method returns a list of related annotation types.
     * Those annotations should not be used with the given annotaton type.
     */
    protected Class<? extends Annotation>[] relatedAnnotationTypes() {
        return new Class[0];
    }


    /**
     * Returns MethodDescriptors representing All for a given EjbDescriptor.
     *
     * @param ejbDesc
     * @return resulting MethodDescriptor
     */
    private Set<MethodDescriptor> getMethodAllDescriptors(EjbDescriptor ejbDesc) {
        Set<MethodDescriptor> methodAlls = new HashSet<>();
        if (ejbDesc.isRemoteInterfacesSupported() || ejbDesc.isRemoteBusinessInterfacesSupported()) {
            methodAlls.add(new MethodDescriptor(MethodDescriptor.ALL_METHODS, "", MethodDescriptor.EJB_REMOTE));
            if (ejbDesc.isRemoteInterfacesSupported()) {
                methodAlls.add(new MethodDescriptor(MethodDescriptor.ALL_METHODS, "", MethodDescriptor.EJB_HOME));
            }
        }

        if (ejbDesc.isLocalInterfacesSupported() || ejbDesc.isLocalBusinessInterfacesSupported()) {
            methodAlls.add(new MethodDescriptor(MethodDescriptor.ALL_METHODS, "", MethodDescriptor.EJB_LOCAL));
            if (ejbDesc.isLocalInterfacesSupported()) {
                methodAlls.add(new MethodDescriptor(MethodDescriptor.ALL_METHODS, "", MethodDescriptor.EJB_LOCALHOME));
            }
        }

        if (ejbDesc.isLocalBean()) {
            methodAlls.add(new MethodDescriptor(MethodDescriptor.ALL_METHODS, "", MethodDescriptor.EJB_LOCAL));
        }

        if (ejbDesc.hasWebServiceEndpointInterface()) {
            methodAlls.add(new MethodDescriptor(MethodDescriptor.ALL_METHODS, "", MethodDescriptor.EJB_WEB_SERVICE));
        }

        return methodAlls;
    }


    /**
     * @param methodDesc
     * @param ejbDesc
     * @return whether the given methodDesc has permission defined in ejbDesc
     */
    private boolean hasMethodPermissionsFromDD(MethodDescriptor methodDesc, EjbDescriptor ejbDesc) {
        Map<MethodPermission, ArrayList<MethodDescriptor>> methodPermissionsFromDD = ejbDesc
            .getMethodPermissionsFromDD();
        if (methodPermissionsFromDD != null) {
            Set<MethodDescriptor> allMethods = ejbDesc.getMethodDescriptors();
            for (List<MethodDescriptor> mdObjs : methodPermissionsFromDD.values()) {
                for (MethodDescriptor md : mdObjs) {
                    for (MethodDescriptor style3Md : md.doStyleConversion(ejbDesc, allMethods)) {
                        LOG.log(Level.FINEST, "Comparing style3Md: {0} and methodDesc: {1}", new Object[] {style3Md, methodDesc});
                        if (methodDesc.equals(style3Md)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }


    /**
     * This method checks whether annotations are compatible.
     * One cannot have two or more of the @DenyAll, @PermitAll, @RoleAllowed.
     *
     * @param ainfo
     * @return validity
     */
    private boolean validateAccessControlAnnotations(AnnotationInfo ainfo) throws AnnotationProcessorException {
        boolean validity = true;
        AnnotatedElement ae = ainfo.getAnnotatedElement();

        int count = 0;
        count += (ae.isAnnotationPresent(RolesAllowed.class) ? 1 : 0);
        if (ae.isAnnotationPresent(DenyAll.class)) {
            count += 1;
        }

        // continue the checking if not already more than one
        if (count < 2 && ae.isAnnotationPresent(PermitAll.class)) {
            count++;
        }

        if (count > 1) {
            log(Level.SEVERE, ainfo,
                I18N.getLocalString(
                "enterprise.deployment.annotation.handlers.morethanoneauthannotation",
                "One cannot have more than one of @RolesAllowed, @PermitAll, @DenyAll in the same AnnotatedElement."));
            validity = false;
        }

        return validity;
    }
}
