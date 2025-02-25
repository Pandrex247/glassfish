/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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

package com.sun.enterprise.glassfish.bootstrap;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.stream.Collectors;


/**
 * {@link URLClassLoader} with overriden {@link #toString()} method, so it prints list of managed
 * URLs
 *
 * @author David Matejcek
 */
public class GlassfishUrlClassLoader extends URLClassLoader {

    /**
     * Initializes the internal classpath.
     *
     * @param urls
     * @param parent
     */
    public GlassfishUrlClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }


    /**
     * Returns class name, hash code and list of managed urls.
     */
    @Override
    public String toString() {
        return getClass().getName() + "@" + Integer.toHexString(hashCode()) + ": "
            + Arrays.stream(getURLs()).collect(Collectors.toList());
    }
}
