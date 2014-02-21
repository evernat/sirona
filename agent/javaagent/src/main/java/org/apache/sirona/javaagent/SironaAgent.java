/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sirona.javaagent;

import org.apache.sirona.javaagent.logging.SironaAgentLogging;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

public class SironaAgent {

    private static final boolean FORCE_RELOAD = Boolean.getBoolean("sirona.javaagent.force.reload");

    public static void premain(final String agentArgs, final Instrumentation instrumentation) {
        agentmain(agentArgs, instrumentation);
    }

    // all is done by reflection cause we change classloader to be able to enhance JVM too
    public static void agentmain(final String agentArgs, final Instrumentation instrumentation) {
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            final String resource = SironaAgent.class.getName().replace('.', '/') + ".class";
            final URL agentUrl = loader.getResource(resource);
            if (agentUrl != null) {
                final String file = agentUrl.getFile();
                final int endIndex = file.indexOf('!');
                if (endIndex > 0) {
                    final String realPath = decode(new URL(file.substring(0, endIndex)).getFile());
                    instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(realPath));
                } // else javaagent not set on the JVM so ignoring appendToSystemClassLoaderSearch
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }

        final String libs = extractConfig(agentArgs, "libs=");
        if (libs != null) {
            final File root = new File(libs);
            if (root.exists()) {
                final File[] children = root.listFiles();
                if (children != null) {
                    for (final File f : children) {
                        if (!f.isDirectory()) {
                            try {
                                System.out.println("load file:" + f.getPath());
                                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(f));
                            } catch (final IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }

        try { // eager init of static blocks
            Class.forName("org.apache.sirona.configuration.Configuration", true, loader);
            Class.forName("org.apache.sirona.javaagent.AgentContext", true, loader);
        } catch (final Exception e) {
            e.printStackTrace();
        }

        try {
            // setup agent parameters
            Class<?> clazz = Class.forName("org.apache.sirona.javaagent.AgentContext", true, loader);
            Method addAgentParameterMethod = clazz.getMethod( "addAgentParameter", new Class[]{ String.class, String.class } );
            Map<String,String> agentParameters=extractParameters( agentArgs );
            for (Map.Entry<String,String> entry : agentParameters.entrySet() ){
                addAgentParameterMethod.invoke( null, new String[]{entry.getKey(), entry.getValue() == null ? "" : entry.getValue()} );

            }
        } catch ( final Exception e ) {
            e.printStackTrace();
        }


        try {
            final SironaTransformer transformer = SironaTransformer.class.cast(loader.loadClass("org.apache.sirona.javaagent.SironaTransformer").newInstance());
            instrumentation.addTransformer(transformer, instrumentation.isRetransformClassesSupported());

            final Class<? extends Annotation> instrumentedMarker = (Class<? extends Annotation>) loader.loadClass("org.apache.sirona.javaagent.Instrumented");
            final Class<?> listener = loader.loadClass("org.apache.sirona.javaagent.spi.InvocationListener");
            if (instrumentation.isRetransformClassesSupported() && FORCE_RELOAD) {
                for (final Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                    if (!clazz.isArray()
                            && !listener.isAssignableFrom(clazz)
                            && clazz.getAnnotation(instrumentedMarker) == null
                            && instrumentation.isModifiableClass(clazz)) {
                        try {

                            SironaAgentLogging.debug( "reload clazz:" + clazz.getName() );

                            instrumentation.retransformClasses(clazz);
                        } catch (final Exception e) {
                            System.err.println("Can't instrument: " + clazz.getName() + "[" + e.getMessage() + "]");
                            if (SironaAgentLogging.AGENT_DEBUG) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            } else {
                if (SironaAgentLogging.AGENT_DEBUG){
                    System.out.println("do not reload classes");
                }
            }
        } catch (final Exception e) {
            if (SironaAgentLogging.AGENT_DEBUG) {
                System.out.println( "finished instrumentation setup with exception:" + e.getMessage() );
            }
            e.printStackTrace();
        }
    }


    private SironaAgent() {
        // no-op
    }

    private static String decode(final String fileName) {
        if (fileName.indexOf('%') == -1) {
            return fileName;
        }

        final StringBuilder result = new StringBuilder(fileName.length());
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < fileName.length();) {
            final char c = fileName.charAt(i);
            if (c == '%') {
                out.reset();
                do {
                    if (i + 2 >= fileName.length()) {
                        throw new IllegalArgumentException("Incomplete % sequence at: " + i);
                    }

                    int d1 = Character.digit(fileName.charAt(i + 1), 16);
                    int d2 = Character.digit(fileName.charAt(i + 2), 16);

                    if (d1 == -1 || d2 == -1) {
                        throw new IllegalArgumentException("Invalid % sequence (" + fileName.substring(i, i + 3) + ") at: " + String.valueOf(i));
                    }
                    out.write((byte) ((d1 << 4) + d2));
                    i += 3;
                } while (i < fileName.length() && fileName.charAt(i) == '%');
                result.append(out.toString());
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    private static String extractConfig(final String agentArgs, final String startStr) {
        if (agentArgs != null && agentArgs.contains(startStr)) {
            final int start = agentArgs.indexOf(startStr) + startStr.length();
            final int separator = agentArgs.indexOf('|', start);
            final int endIdx;
            if (separator > 0) {
                endIdx = separator;
            } else {
                endIdx = agentArgs.length();
            }
            return agentArgs.substring(start, endIdx);
        }
        return null;
    }

    /**
     *
     * @param agentArgs foo=bar|beer=palepale|etc...
     * @return
     */
    protected static Map<String, String> extractParameters(String agentArgs){
        if(agentArgs==null||agentArgs.length()<1){
            return Collections.emptyMap();
        }

        String[] separatorSplitted = agentArgs.split( "\\|" );

        Map<String,String> params = new HashMap<String, String>( separatorSplitted.length / 2 );

        for (final String agentArg:separatorSplitted){
            int idx = agentArg.indexOf( '=' );
            if (idx>=0){
                String key = agentArg.substring( 0, idx);
                String value = agentArg.substring(idx+1, agentArg.length());
                params.put( key, value);
            } else {
                params.put( agentArg, "" );
            }
        }

        return params;
    }
}
