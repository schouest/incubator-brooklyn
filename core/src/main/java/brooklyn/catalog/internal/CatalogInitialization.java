/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.catalog.internal;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.BrooklynCatalog;
import brooklyn.catalog.CatalogItem;
import brooklyn.config.BrooklynServerConfig;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.util.ResourceUtils;
import brooklyn.util.collections.MutableList;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.guava.Maybe;
import brooklyn.util.net.Urls;
import brooklyn.util.text.Strings;
import brooklyn.util.yaml.Yamls;

import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.collect.Iterables;

@Beta
public class CatalogInitialization {

    /*

    A1) if not persisting, go to B1
    A2) if --catalog-reset, delete persisted catalog items
    A3) read persisted catalog items (possibly deleted in A2), go to C1
    A4) go to B1

    B1) look for --catalog-initial, if so read it, then go to C1
    B2) look for BrooklynServerConfig.BROOKLYN_CATALOG_URL, if so, read it, supporting YAML or XML (warning if XML), then go to C1
    B3) look for ~/.brooklyn/catalog.bom, if exists, read it then go to C1
    B4) look for ~/.brooklyn/brooklyn.xml, if exists, warn, read it then go to C1
    B5) read all classpath://brooklyn/default.catalog.bom items, if they exist (and for now they will)
    B6) go to C1

    C1) if --catalog-add, read and add those items

    D1) if persisting, read the rest of the persisted items (entities etc)

     */

    private static final Logger log = LoggerFactory.getLogger(CatalogInitialization.class);
    
    String initialUri;
    boolean reset;
    String additionsUri;
    boolean force;

    boolean disallowLocal = false;
    List<Function<ManagementContext, Void>> callbacks = MutableList.of();
    AtomicInteger runCount = new AtomicInteger();
    
    public CatalogInitialization(String initialUri, boolean reset, String additionUri, boolean force) {
        this.initialUri = initialUri;
        this.reset = reset;
        this.additionsUri = additionUri;
        this.force = force;
    }
    
    public CatalogInitialization() {
        this(null, false, null, false);
    }

    public CatalogInitialization addPopulationCallback(Function<ManagementContext, Void> callback) {
        callbacks.add(callback);
        return this;
    }

    public boolean isInitialResetRequested() {
        return reset;
    }

    public int getRunCount() {
        return runCount.get();
    }
    
    public boolean hasRun() {
        return getRunCount()>0;
    }

    /** makes or updates the mgmt catalog, based on the settings in this class */
    public void populateCatalog(ManagementContext managementContext, boolean needsInitial, Collection<CatalogItem<?, ?>> optionalItemsForResettingCatalog) {
        try {
            BasicBrooklynCatalog catalog;
            Maybe<BrooklynCatalog> cm = ((ManagementContextInternal)managementContext).getCatalogIfSet();
            if (cm.isAbsent()) {
                if (hasRun()) {
                    log.warn("Odd: catalog initialization has run but management context has no catalog; re-creating");
                }
                catalog = new BasicBrooklynCatalog(managementContext);
                setCatalog(managementContext, catalog, "Replacing catalog with newly populated catalog", true);
            } else {
                if (!hasRun()) {
                    log.warn("Odd: catalog initialization has not run but management context has a catalog; re-populating");
                }
                catalog = (BasicBrooklynCatalog) cm.get();
            }

            populateCatalog(managementContext, catalog, needsInitial, true, optionalItemsForResettingCatalog);
            
        } finally {
            runCount.incrementAndGet();
        }
    }

    private void populateCatalog(ManagementContext managementContext, BasicBrooklynCatalog catalog, boolean needsInitial, boolean runCallbacks, Collection<CatalogItem<?, ?>> optionalItemsForResettingCatalog) {
        applyCatalogLoadMode(managementContext);
        
        if (optionalItemsForResettingCatalog!=null) {
            catalog.reset(optionalItemsForResettingCatalog);
        }
        
        if (needsInitial) {
            populateInitial(catalog, managementContext);
        }
        
        populateAdditions(catalog, managementContext);

        if (runCallbacks) {
            populateViaCallbacks(catalog, managementContext);
        }
    }

    private enum PopulateMode { YAML, XML, AUTODETECT }
    
    protected void populateInitial(BasicBrooklynCatalog catalog, ManagementContext managementContext) {
        if (disallowLocal) {
            if (!hasRun()) {
                log.debug("CLI initial catalog not being read with disallow-local mode set.");
            }
            return;
        }

//        B1) look for --catalog-initial, if so read it, then go to C1
//        B2) look for BrooklynServerConfig.BROOKLYN_CATALOG_URL, if so, read it, supporting YAML or XML (warning if XML), then go to C1
//        B3) look for ~/.brooklyn/catalog.bom, if exists, read it then go to C1
//        B4) look for ~/.brooklyn/brooklyn.xml, if exists, warn, read it then go to C1
//        B5) read all classpath://brooklyn/default.catalog.bom items, if they exist (and for now they will)
//        B6) go to C1

        if (initialUri!=null) {
            populateInitialFromUri(catalog, managementContext, initialUri, PopulateMode.AUTODETECT);
            return;
        }
        
        String catalogUrl = managementContext.getConfig().getConfig(BrooklynServerConfig.BROOKLYN_CATALOG_URL);
        if (Strings.isNonBlank(catalogUrl)) {
            populateInitialFromUri(catalog, managementContext, catalogUrl, PopulateMode.AUTODETECT);
            return;
        }
        
        catalogUrl = Urls.mergePaths(BrooklynServerConfig.getMgmtBaseDir( managementContext.getConfig() ), "catalog.bom");
        if (new File(catalogUrl).exists()) {
            populateInitialFromUri(catalog, managementContext, "file:"+catalogUrl, PopulateMode.YAML);
            return;
        }
        
        catalogUrl = Urls.mergePaths(BrooklynServerConfig.getMgmtBaseDir( managementContext.getConfig() ), "catalog.xml");
        if (new File(catalogUrl).exists()) {
            populateInitialFromUri(catalog, managementContext, "file:"+catalogUrl, PopulateMode.XML);
            return;
        }

        // TODO scan for default.catalog.bom files and add all of them
        
//        // TODO optionally scan for classpath items
//        // retry, either an error, or was blank
//        dto = CatalogDto.newDefaultLocalScanningDto(CatalogClasspathDo.CatalogScanningModes.ANNOTATIONS);
//        if (log.isDebugEnabled()) {
//            log.debug("Loaded default (local classpath) catalog: " + catalogDo);
//        }
        
        return;
    }
    
    private void populateInitialFromUri(BasicBrooklynCatalog catalog, ManagementContext managementContext, String catalogUrl, PopulateMode mode) {
        log.debug("Loading initial catalog from {}", catalogUrl);

        Exception problem = null;
        Object result = null;
        
        String contents = null;
        try {
            contents = new ResourceUtils(this).getResourceAsString(catalogUrl);
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            if (problem==null) problem = e;
        }

        if (contents!=null && (mode==PopulateMode.YAML || mode==PopulateMode.AUTODETECT)) {
            // try YAML first
            try {
                catalog.reset(MutableList.<CatalogItem<?,?>>of());
                result = catalog.addItems(contents);
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                if (problem==null) problem = e;
            }
        }
        
        if (result==null && contents!=null && (mode==PopulateMode.XML || mode==PopulateMode.AUTODETECT)) {
            // then try XML
            CatalogDto dto = null;
            try {
                dto = CatalogDto.newDtoFromXmlContents(contents, catalogUrl);
                problem = null;
            } catch (Exception e) {
                Exceptions.propagateIfFatal(e);
                if (problem==null) problem = e;
            }
            if (dto!=null) {
                catalog.reset(dto);
            }
        }
        
        if (result!=null) {
            log.debug("Loaded initial catalog from {}: {}", catalogUrl, result);
        }
        if (problem!=null) {
            log.warn("Error importing catalog from " + catalogUrl + ": " + problem, problem);
            // TODO inform mgmt of error
        }

    }

    protected void populateAdditions(BasicBrooklynCatalog catalog, ManagementContext mgmt) {
        if (Strings.isNonBlank(additionsUri)) {
            if (disallowLocal) {
                if (!hasRun()) {
                    log.warn("CLI additions supplied but not supported in disallow-local mode; ignoring.");
                }
                return;
            }   
            if (!hasRun()) {
                log.debug("Adding to catalog from CLI: "+additionsUri+" (force: "+force+")");
            }
            Iterable<? extends CatalogItem<?, ?>> items = catalog.addItems(
                new ResourceUtils(this).getResourceAsString(additionsUri), force);
            
            if (!hasRun())
                log.debug("Added to catalog from CLI: "+items);
            else
                log.debug("Added to catalog from CLI: count "+Iterables.size(items));
        }
    }

    protected void populateViaCallbacks(BasicBrooklynCatalog catalog, ManagementContext managementContext) {
        for (Function<ManagementContext, Void> callback: callbacks)
            callback.apply(managementContext);
    }

    private boolean setFromCatalogLoadMode = false;
    /** @deprecated since introduced in 0.7.0, only for legacy compatibility with 
     * {@link CatalogLoadMode} {@link BrooklynServerConfig#CATALOG_LOAD_MODE},
     * allowing control of catalog loading from a brooklyn property */
    @Deprecated
    public void applyCatalogLoadMode(ManagementContext managementContext) {
        if (setFromCatalogLoadMode) return;
        setFromCatalogLoadMode = true;
        Maybe<Object> clmm = ((ManagementContextInternal)managementContext).getConfig().getConfigRaw(BrooklynServerConfig.CATALOG_LOAD_MODE, false);
        if (clmm.isAbsent()) return;
        brooklyn.catalog.CatalogLoadMode clm = TypeCoercions.coerce(clmm.get(), brooklyn.catalog.CatalogLoadMode.class);
        log.warn("Legacy CatalogLoadMode "+clm+" set: applying, but this should be changed to use new CLI --catalogXxx commands");
        switch (clm) {
        case LOAD_BROOKLYN_CATALOG_URL:
            reset = true;
            break;
        case LOAD_BROOKLYN_CATALOG_URL_IF_NO_PERSISTED_STATE:
            // now the default
            break;
        case LOAD_PERSISTED_STATE:
            disallowLocal = true;
            break;
        }
    }

    /** makes the catalog, warning if persistence is on and hasn't run yet 
     * (as the catalog will be subsequently replaced) */
    @Beta
    public BrooklynCatalog getCatalogPopulatingBestEffort(ManagementContext managementContext) {
        Maybe<BrooklynCatalog> cm = ((ManagementContextInternal)managementContext).getCatalogIfSet();
        if (cm.isPresent()) return cm.get();

        BrooklynCatalog oldC = setCatalog(managementContext, new BasicBrooklynCatalog(managementContext),
            "Request to make local catalog early, but someone else has created it, reverting to that", false);
        if (oldC==null) {
            // our catalog was added, so run population
            // NB: we need the catalog to be saved already so that we can run callbacks
            populateCatalog(managementContext, (BasicBrooklynCatalog) managementContext.getCatalog(), true, true, null);
        }
        
        return managementContext.getCatalog();
    }

    /** Sets the catalog in the given management context, warning and choosing appropriately if one already exists. 
     * Returns any previously existing catalog (whether or not changed). */
    @Beta
    public static BrooklynCatalog setCatalog(ManagementContext managementContext, BrooklynCatalog catalog, String messageIfAlready, boolean preferNew) {
        Maybe<BrooklynCatalog> cm;
        synchronized (managementContext) {
            cm = ((ManagementContextInternal)managementContext).getCatalogIfSet();
            if (cm.isAbsent()) {
                ((ManagementContextInternal)managementContext).setCatalog(catalog);
                return null;
            }
            if (preferNew) {
                // set to null first to prevent errors
                ((ManagementContextInternal)managementContext).setCatalog(null);
                ((ManagementContextInternal)managementContext).setCatalog(catalog);
            }
        }
        if (Strings.isNonBlank(messageIfAlready)) {
            log.warn(messageIfAlready);
        }
        return cm.get();
    }
    
}
