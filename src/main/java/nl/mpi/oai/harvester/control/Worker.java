/*
 * Copyright (C) 2014, The Max Planck Institute for
 * Psycholinguistics.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * A copy of the GNU General Public License is included in the file
 * LICENSE-gpl-3.0.txt. If that file is missing, see
 * <http://www.gnu.org/licenses/>.
 */

package nl.mpi.oai.harvester.control;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import nl.mpi.oai.harvester.StaticProvider;
import nl.mpi.oai.harvester.action.ActionSequence;
import nl.mpi.oai.harvester.harvesting.*;
import nl.mpi.oai.harvester.Provider;
import nl.mpi.oai.harvester.metadata.MetadataFactory;
import org.apache.log4j.Logger;

/**
 * This class represents a single processing thread in the harvesting actions
 * workflow. In practice one worker takes care of one provider. The worker
 * applies a scenario for harvesting: first get record identifiers, after that
 * get the records individually. Alternatively, in a second scenario, it gets
 * multiple records per OAI request directly.
 *
 * @author Lari Lampen (MPI-PL), extensions by Kees Jan van de Looij (MPI-PL).
 */
class Worker implements Runnable {
    
    private static final Logger logger = Logger.getLogger(Worker.class);
    
    /** A standard semaphore is used to track the number of running threads. */
    private static Semaphore semaphore;

    /** The provider this worker deals with. */
    private final Provider provider;

    /** List of actionSequences to be applied to the harvested metadata. */
    private final List<ActionSequence> actionSequences;

    /* Harvesting scenario to be applied. ListIdentifiers: first, based on
       endpoint data and prefix, get a list of identifiers, and after that
       retrieve each record in the list individually. ListRecords: skip the
       list, retrieve multiple records per request.
     */
    private final String scenarioName;

    /**
     * Set the maximum number of concurrent worker threads.
     * 
     * @param num number of running threads that may not be exceeded
     */
    public static void setConcurrentLimit(int num) {
	semaphore = new Semaphore(num);
    }

    /**
     * Associate a provider and action actionSequences with a scenario
     *
     * @param provider OAI-PMH provider that this thread will harvest
     * @param actionSequences list of actions to take on harvested metadata
     * @param scenarioName the scenario to be applied
     *
     */
    public Worker(Provider provider, List<ActionSequence> actionSequences,
                  String scenarioName) {
	this.provider  = provider;
	this.actionSequences = actionSequences;
    this.scenarioName = scenarioName;
    }

    /**
     * <br>Start this worker thread <br><br>
     *
     * This method will block for as long as necessary until a thread can be
     * started without violating the limit.
     */
    public void startWorker() {
	for (;;) {
	    try {
		semaphore.acquire();
		break;
	    } catch (InterruptedException e) { }
	}
	Thread t = new Thread(this);
	t.start();
    }

    @Override
    public void run() {
        provider.init();

        boolean done = false;

        // kj: annotate
        MetadataFactory metadataFactory = new MetadataFactory();

        //
        OAIFactory oaiFactory = new OAIFactory();

        logger.info("Processing provider " + provider);
        for (final ActionSequence actionSequence : actionSequences) {

            // list of prefixes provided by the endpoint
            List<String> prefixes;

            Scenario scenario = new Scenario(provider, actionSequence);

            if (provider instanceof StaticProvider) {

                // set type of format harvesting to apply
                Harvesting harvesting = new StaticPrefixHarvesting(
                        oaiFactory,
                        (StaticProvider) provider,
                        actionSequence);

                // get the prefixes
                prefixes = scenario.getPrefixes(harvesting);

                if (prefixes.size() == 0) {
                    done = false;
                } else {
                    // set type of record harvesting to apply
                    harvesting = new StaticRecordListHarvesting(oaiFactory,
                            (StaticProvider) provider, prefixes, metadataFactory);

                    // get the records
                    done = scenario.listRecords(harvesting);
                }
            } else {

                // set type of format harvesting to apply
                Harvesting harvesting = new FormatHarvesting(oaiFactory,
                        provider, actionSequence);

                // get the prefixes
                prefixes = scenario.getPrefixes(harvesting);

                done = (prefixes.size()!= 0);
                if (prefixes.size() == 0) {
                    // no match
                    done = false;
                } else {

                    // determine the type of record harvesting to apply
                    if (scenarioName.equals("ListIdentifiers")) {
                        harvesting = new IdentifierListHarvesting(oaiFactory,
                                provider, prefixes, metadataFactory);

                        // get the records
                        done = scenario.listIdentifiers(harvesting);
                    } else {
                        harvesting = new RecordListHarvesting(oaiFactory,
                                provider, prefixes, metadataFactory);

                        // get the records
                        done = scenario.listRecords(harvesting);
                    }
                }
            }

            // break after an action sequence has completed successfully
            if (done) break;

        }
        logger.info("Processing finished for " + provider);
        semaphore.release();
    }
}
