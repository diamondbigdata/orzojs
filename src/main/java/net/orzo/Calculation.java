/*
 * Copyright (C) 2013 Tomas Machalek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.orzo;

import static net.orzo.Util.normalizePath;

import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.script.ScriptException;

import com.google.common.collect.Lists;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import net.orzo.scripting.EnvParams;
import net.orzo.scripting.JsEngineAdapter;

import net.orzo.service.TaskEvent;
import net.orzo.service.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs map-reduce calculation.
 *
 * @author Tomas Machalek <tomas.machalek@gmail.com>
 */
@SuppressWarnings("restriction")
public class Calculation extends Observable {

    /**
     *
     */
    private static final Logger LOG = LoggerFactory
            .getLogger(Calculation.class);

    private final CalculationParams params;

    /**
     *
     */
    private final List<String> modulesPaths;

    /**
     *
     */
    private final String[] inputValues;

    private final double startTime;

    private final SharedServices sharedServices;

    /**
     *
     */
    public Calculation(CalculationParams params, SharedServices sharedServices) {
        this.startTime = System.currentTimeMillis() / 1000.;
        this.params = params;
        this.inputValues = params.inputValues;
        this.modulesPaths = new ArrayList<>();
        this.modulesPaths.add(params.workingDirModulesPath);
        if (params.optionalModulesPath != null) {
            this.modulesPaths.add(params.optionalModulesPath);
        }
        this.sharedServices = sharedServices;
    }

    /**
     * Starts and controls the calculation.
     */
    public Object run() throws CalculationException {
        IntermediateResults currentResults;
        ScriptObjectMirror prepareData = runPrepare();
        int numReduceFunctions = ((Double)prepareData.get("numReduceFunctions")).intValue();
		currentResults = runMap(prepareData);

        for (int i = 0; i < numReduceFunctions && currentResults.size() > 0; i++) {
			currentResults = runReduce(prepareData, currentResults, i);
		}
		return runFinish(currentResults);

    }

    private EnvParams createEnvParams() {
        EnvParams envParams = new EnvParams();
        envParams.workingDir = normalizePath(System.getProperty("user.dir"));
        envParams.scriptName = normalizePath(this.params.userScript
                .getFullyQualifiedName());
        envParams.inputArgs = this.inputValues;
        envParams.modulesPaths = this.modulesPaths;
        envParams.startTimestamp = this.startTime;
        return envParams;
    }

    /**
     * Runs preparation phase when user script is loaded (= all the respective
     * functions are registered but no real processing is done yet).
     */
    private ScriptObjectMirror runPrepare() throws CalculationException {
        JsEngineAdapter jsEngine = new JsEngineAdapter(createEnvParams(),
                this.sharedServices);
        jsEngine.beginWork();
        try {
            jsEngine.runCode(this.params.calculationScript,
                    this.params.userenvScript, this.params.datalibScript);
            jsEngine.runFunction("prepare");
            jsEngine.runCode(this.params.userScript);
            return (ScriptObjectMirror) jsEngine.runFunction("getParams");

        } catch (NoSuchMethodException | ScriptException ex) {
            throw new CalculationException("Failed to perform PREPARE: "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * Runs the MAP phase. Input is given by user's script,output is
     * {@link IntermediateResults} which stores respective keys and lists of
     * values.
     *
     * @return key => [value1, value2,..., valueN] for all emitted keys and
     * values
     */
    private IntermediateResults runMap(ScriptObjectMirror conf)
            throws CalculationException {
        ExecutorService executor;
        List<Future<IntermediateResults>> threadList = new ArrayList<>();
        Callable<IntermediateResults> worker;
        IntermediateResults mapResults = new IntermediateResults();
        int numWorkers = (int) conf.get("numChunks");

        setChanged();
        notifyObservers(new TaskEvent(TaskStatus.RUNNING_MAP));

        executor = Executors.newFixedThreadPool(numWorkers);
        EnvParams workerEnvParams;
        for (int i = 0; i < numWorkers; i++) {
            workerEnvParams = createEnvParams();
            workerEnvParams.workerId = i;
            JsEngineAdapter jsEngine = new JsEngineAdapter(workerEnvParams,
                    this.sharedServices, new IntermediateResults());
            worker = new MapWorker(jsEngine, this.params);
            Future<IntermediateResults> submit = executor.submit(worker);
            threadList.add(submit);
        }

        List<Exception> errors = new ArrayList<>();
        for (int i = 0; i < threadList.size(); i++) {
            try {
                mapResults.addAll(threadList.get(i).get());
                threadList.get(i).get().getData().clear(); // TODO does this make a difference?

            } catch (InterruptedException | ExecutionException e) {
                errors.add(e);
                LOG.error(String.format("Worker[%d]: %s", i, e.getMessage()), e);
            }
        }
        executor.shutdown();
        if (errors.size() > 0) {
            throw new ParallelException("Failed to perform MAP", errors);
        }
        return mapResults;
    }

    /**
     * Runs the REDUCE phase. Input is given by the result of thee MAP phase.
     *
     * @return key => "object" for all emitted keys and values
     */
    private IntermediateResults runReduce(ScriptObjectMirror prepareData,
            IntermediateResults mapResults, int functionIdx) throws ParallelException {
        setChanged();
        notifyObservers(new TaskEvent(TaskStatus.RUNNING_REDUCE));

        ExecutorService executor;
        List<Future<IntermediateResults>> threadList = new ArrayList<>();
        IntermediateResults reduceResults = new IntermediateResults();
        int numWorkers = (int) prepareData.get("numReduceWorkers");

        List<List<String>> splitKeys = groupResults(mapResults, numWorkers);
        numWorkers = splitKeys.size(); // groupResults may have decided to optimize num of groups
        LOG.info(String.format("Calculated number of reduce workers: %d", numWorkers));

        executor = Executors.newFixedThreadPool(numWorkers);
        for (int i = 0; i < numWorkers; i++) {
            EnvParams workerEnvParams = createEnvParams();
            workerEnvParams.workerId = i;
            JsEngineAdapter jsEngine = new JsEngineAdapter(workerEnvParams,
                    this.sharedServices, new IntermediateResults());
            ReduceWorker reduceWorker = new ReduceWorker(jsEngine,
                    mapResults, splitKeys.get(i), functionIdx, this.params);
            Future<IntermediateResults> submit = executor.submit(reduceWorker);
            threadList.add(submit);
        }

        List<Exception> errors = new ArrayList<>();
        for (int i = 0; i < numWorkers; i++) {
            try {
                reduceResults.addAll(threadList.get(i).get());

            } catch (InterruptedException | ExecutionException e) {
                errors.add(e);
                LOG.error(String.format("Worker[%d]: %s", i, e.getMessage()), e);
            }
        }
        executor.shutdown();
        if (errors.size() > 0) {
            throw new ParallelException("Failed to perform REDUCE.", errors);
        }
        return reduceResults;
    }

    private Object runFinish(IntermediateResults reduceResults)
            throws CalculationException {
        setChanged();
        notifyObservers(new TaskEvent(TaskStatus.RUNNING_FINISH));

        Object ans;
        EnvParams envParams = createEnvParams();
        JsEngineAdapter jse = new JsEngineAdapter(envParams, this.sharedServices);
        FinalResults fr = new FinalResults(reduceResults);

        jse.beginWork();
        try {
            jse.runCode(this.params.calculationScript,
                    this.params.userenvScript, this.params.datalibScript);
            jse.runFunction("initFinish");
            jse.runCode(this.params.userScript);
            ans = jse.runFunction("runFinish", fr);

        } catch (NoSuchMethodException | ScriptException ex) {
            throw new CalculationException("Failed to perform FINISH: "
                    + ex.getMessage(), ex);
        }
        jse.endWork();
        return ans;
    }

    /**
     */
    private List<List<String>> groupResults(
            IntermediateResults originalResults, int numGroups) {
        /*
         * Note: In terms of performance, it is essential to split
         * keys so each worker has roughly the same amount of items to
         * process. Unfortunately, the current solution does not contain
         * any such optimization.
         */
        List<String> keys = new ArrayList<>(originalResults.keys());
        int calcNumGroups = Math.min(numGroups, keys.size()); // cannot use more workers than keys

        if (keys.size() > 0) {
            int itemsPerChunk = (int) Math.ceil(keys.size() / (float)calcNumGroups);
            return Lists.partition(keys, itemsPerChunk);

        } else {
            List<List<String>> ans = new ArrayList();
            ans.add(keys);
            return ans;
        }
    }

}
