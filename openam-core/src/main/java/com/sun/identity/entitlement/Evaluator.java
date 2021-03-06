/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008 Sun Microsystems Inc. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * https://opensso.dev.java.net/public/CDDLv1.0.html or
 * opensso/legal/CDDLv1.0.txt
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at opensso/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: Evaluator.java,v 1.2 2009/09/10 16:35:38 veiming Exp $
 *
 * Portions copyright 2013-2016 ForgeRock AS.
 */
package com.sun.identity.entitlement;

import static org.forgerock.openam.entitlement.PolicyConstants.SUPER_ADMIN_SUBJECT;
import static org.forgerock.openam.entitlement.utils.EntitlementUtils.getApplicationService;
import static org.forgerock.openam.utils.Time.*;

import com.sun.identity.shared.Constants;
import com.sun.identity.shared.configuration.SystemPropertiesManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Subject;
import org.forgerock.guice.core.InjectorHolder;
import org.forgerock.openam.entitlement.monitoring.EntitlementConfigurationWrapper;
import org.forgerock.openam.entitlement.monitoring.PolicyMonitor;
import org.forgerock.openam.entitlement.monitoring.PolicyMonitoringType;

/**
 * The class evaluates entitlement request and provides decisions. The evaluation of a policy depends on the following
 * contextual information:
 * <ul>
 *     <li>realm: The realm the policy needs to be evaluated in.</li>
 *     <li>subject: The subject that attempts to access a particular resource.</li>
 *     <li>resourceNames: The resources the subject attempts to access.</li>
 *     <li>environment: Additional information about the environment within which the policy should be evaluated. Note
 *     that certain environment/subject conditions may depend on certain fields to be present in this map. One such
 *     example would be the LDAP Filter Condition having access to the {@link
 *     com.sun.identity.policy.PolicyEvaluator#REALM_DN} field containing the realm's DN representation.</li>
 * </ul>
 *
 * Additionally, the evaluation will also take into account the name of the policy set (a.k.a. application) used when
 * creating the Evaluator instance.
 *
 * 
 */
public class Evaluator {

    public static final int DEFAULT_POLICY_EVAL_THREAD = 10;
    private final Subject adminSubject;
    private final String applicationName;
    private final PolicyMonitor policyMonitor;
    private final EntitlementConfigurationWrapper configWrapper;

    /**
     * Constructor to create an evaluator the default service type.
     *
     * @param subject Subject who credential is used for performing the evaluation.
     * @throws EntitlementException if any other abnormal condition occurred.
     */
    public Evaluator(Subject subject) throws EntitlementException {
        this(subject, ApplicationTypeManager.URL_APPLICATION_TYPE_NAME);
    }

    /**
     * Constructor to create an evaluator given the service type.
     *
     * @param subject Subject who credential is used for performing the evaluation.
     * @param applicationName the name of the aplication for which this evaluator can be used.
     * @throws EntitlementException if any other abnormal condition occurred.
     */
    public Evaluator(Subject subject, String applicationName) throws EntitlementException {
        adminSubject = subject;
        this.applicationName = applicationName;
        policyMonitor = getPolicyMonitor();
        configWrapper = new EntitlementConfigurationWrapper();
    }

    private PolicyMonitor getPolicyMonitor() {
        //used as no direct access to SystemProperties
        boolean serverMode = Boolean.parseBoolean(SystemPropertiesManager.get(Constants.SERVER_MODE));

        if (serverMode) {
            return InjectorHolder.getInstance(PolicyMonitor.class);
        } else {
            return null;
        }
    }

    /**
     * Returns <code>true</code> if the subject is granted to an entitlement.
     *
     * @param realm Realm name.
     * @param subject Subject who is under evaluation.
     * @param e Entitlement object which describes the resource name and actions.
     * @param envParameters Map of environment parameters.
     * @return <code>true</code> if the subject is granted to an entitlement.
     * @throws EntitlementException if the result cannot be determined.
     */
    public boolean hasEntitlement(
        String realm,
        Subject subject, 
        Entitlement e,
        Map<String, Set<String>> envParameters
    ) throws EntitlementException {

        PrivilegeEvaluator evaluator = new PrivilegeEvaluator();
        boolean result = evaluator.hasEntitlement(realm,
            adminSubject, subject, applicationName, e, envParameters);

        return result;
    }

    /**
     * Returns a list of entitlements for a given subject, resource names and environment.
     *
     * @param realm Realm Name.
     * @param subject Subject who is under evaluation.
     * @param resourceNames Resource names.
     * @param environment Environment parameters.
     * @return a list of entitlements for a given subject, resource name and environment.
     * @throws EntitlementException if the result cannot be determined.
     *
     * 
     */
    public List<Entitlement> evaluate(
        String realm,
        Subject subject,
        Set<String> resourceNames,
        Map<String, Set<String>> environment
    ) throws EntitlementException {
        if ((resourceNames == null) || resourceNames.isEmpty()) {
            throw new EntitlementException(424);
        }

        List<Entitlement> results = new ArrayList<Entitlement>();

        for (String res : resourceNames) {
            List<Entitlement> r = evaluate(realm, subject, res, environment,
                false);
            if ((r != null) && !r.isEmpty()) {
                results.addAll(r);
            }
        }
        return results;
    }

    /**
     * Returns a list of entitlements for a given subject, resource name
     * and environment.
     *
     * @param realm Realm Name.
     * @param subject Subject who is under evaluation.
     * @param resourceName Resource name.
     * @param environment Environment parameters.
     * @param recursive <code>true</code> to perform evaluation on sub resources from the given resource name.
     * @return a list of entitlements for a given subject, resource name and environment.
     * @throws EntitlementException if the result cannot be determined.
     *
     * 
     */
    public List<Entitlement> evaluate(
            String realm,
            Subject subject,
            String resourceName,
            Map<String, Set<String>> environment,
            boolean recursive
    ) throws EntitlementException {

        long startTime = currentTimeMillis();

        // Delegation to applications is currently not configurable, passing super admin (see AME-4959)
        Application application = getApplicationService(SUPER_ADMIN_SUBJECT, realm).getApplication(applicationName);

        if (application == null) {
            // App retrieval error.
            throw new EntitlementException(EntitlementException.APP_RETRIEVAL_ERROR, new String[] {realm});
        }

        // Normalise the incoming resource URL.
        String normalisedResourceName = application.getResourceComparator().canonicalize(resourceName);

        PrivilegeEvaluator evaluator = new PrivilegeEvaluator();
        List<Entitlement> results = evaluator.evaluate(realm, adminSubject, subject,
                applicationName, normalisedResourceName, resourceName, environment, recursive);

        if (configWrapper.isMonitoringRunning()) {
            policyMonitor.addEvaluation(currentTimeMillis() - startTime, realm, applicationName, resourceName,
                    subject, recursive ? PolicyMonitoringType.SUBTREE : PolicyMonitoringType.SELF);
        }

        return results;
    }

    /**
     * Returns application name.
     * 
     * @return application name.
     */
    public String getApplicationName() {
        return applicationName;
    }
}

