/*
 * SoapUI, Copyright (C) 2004-2019 SmartBear Software
 *
 * Licensed under the EUPL, Version 1.1 or - as soon as they will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * http://ec.europa.eu/idabc/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the Licence for the specific language governing permissions and limitations
 * under the Licence.
 */

package com.eviware.soapui.impl.wsdl.testcase;

import com.eviware.soapui.SoapUI;
import com.eviware.soapui.impl.wsdl.WsdlTestSuite;
import com.eviware.soapui.impl.wsdl.support.AbstractTestRunner;
import com.eviware.soapui.model.propertyexpansion.DefaultPropertyExpansionContext;
import com.eviware.soapui.model.support.TestRunListenerAdapter;
import com.eviware.soapui.model.testsuite.TestCase;
import com.eviware.soapui.model.testsuite.TestCaseRunContext;
import com.eviware.soapui.model.testsuite.TestCaseRunner;
import com.eviware.soapui.model.testsuite.TestRunListener;
import com.eviware.soapui.model.testsuite.TestSuite;
import com.eviware.soapui.model.testsuite.TestSuite.TestSuiteRunType;
import com.eviware.soapui.model.testsuite.TestSuiteRunListener;
import com.eviware.soapui.model.testsuite.TestSuiteRunner;
import com.eviware.soapui.support.types.StringToObjectMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WSDL TestCase Runner - runs all steps in a testcase and collects performance
 * data
 *
 * @author Ole.Matzura
 */

public class WsdlTestSuiteRunner extends AbstractTestRunner<WsdlTestSuite, WsdlTestSuiteRunContext> implements
        TestSuiteRunner {

    private final Object lock = new Object();
    private final AtomicInteger runCount = new AtomicInteger(-1);
    private volatile boolean running;

    private TestSuiteRunListener[] listeners;
    private Set<TestCaseRunner> finishedRunners = new HashSet<>();
    private Set<TestCaseRunner> activeRunners = new HashSet<>();
    private int currentTestCaseIndex;
    private WsdlTestCase currentTestCase;
    private TestRunListener parallellTestRunListener = new ParallellTestRunListener();

    public WsdlTestSuiteRunner(WsdlTestSuite testSuite, StringToObjectMap properties) {
        super(testSuite, properties);
    }

    public WsdlTestSuiteRunContext createContext(StringToObjectMap properties) {
        return new WsdlTestSuiteRunContext(this, properties);
    }

    public void onCancel(String reason) {
        synchronized (lock) {
            for (TestCaseRunner runner : activeRunners.toArray(new TestCaseRunner[activeRunners.size()])) {
                runner.cancel(reason);
            }
        }
    }

    public void onFail(String reason) {
        synchronized (lock) {
            for (TestCaseRunner runner : activeRunners.toArray(new TestCaseRunner[activeRunners.size()])) {
                runner.fail(reason);
            }
        }
    }

    public void internalRun(WsdlTestSuiteRunContext runContext) throws Exception {
        WsdlTestSuite testSuite = getTestRunnable();

        synchronized (lock) {
            listeners = testSuite.getTestSuiteRunListeners();
            testSuite.runSetupScript(runContext, this);
            if (!isRunning()) {
                return;
            }

            if (testSuite.getTimeout() > 0) {
                startTimeoutTimer(testSuite.getTimeout());
            }

            notifyBeforeRun();
            if (!isRunning()) {
                return;
            }
        }
        if (testSuite.getRunType() == TestSuiteRunType.SEQUENTIAL) {
            runSequential(testSuite, runContext);
        } else if (testSuite.getRunType() == TestSuiteRunType.PARALLEL) {
            long t0 = System.currentTimeMillis();
            runParallel(testSuite, runContext);
            SoapUI.getErrorLog().error(String.valueOf(System.currentTimeMillis() - t0));
        }
    }

    private void runParallel(WsdlTestSuite testSuite, WsdlTestSuiteRunContext runContext) {
        synchronized (lock) {
            currentTestCaseIndex = -1;
            currentTestCase = null;

            int i = 0;
            for (TestCase testCase : testSuite.getTestCaseList()) {
                if (!testCase.isDisabled()) {
                    i++;
                    testCase.addTestRunListener(parallellTestRunListener);
                    notifyBeforeRunTestCase(testCase);
                    runTestCase((WsdlTestCase) testCase, true);
                }
            }
            runCount.set(i);
            running = true;
        }

        while (runCount.get() != 0) {
            synchronized (runCount) {
                try {
                    runCount.wait(1000L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void runSequential(WsdlTestSuite testSuite, WsdlTestSuiteRunContext runContext) {
        currentTestCaseIndex = 0;
        for (; isRunning() && currentTestCaseIndex < testSuite.getTestCaseCount(); currentTestCaseIndex++) {
            currentTestCase = testSuite.getTestCaseAt(currentTestCaseIndex);
            if (!currentTestCase.isDisabled()) {
                notifyBeforeRunTestCase(currentTestCase);
                TestCaseRunner testCaseRunner = runTestCase(currentTestCase, false);
                activeRunners.remove(testCaseRunner);
                finishedRunners.add(testCaseRunner);
                notifyAfterRunTestCase(testCaseRunner);
            }
        }

        updateStatus();
    }

    private void updateStatus() {
        activeRunners.clear();
        for (TestCaseRunner runner : finishedRunners) {
            if (runner.getStatus() == Status.FAILED) {
                setStatus(Status.FAILED);
                break;
            }
        }
    }

    private TestCaseRunner runTestCase(WsdlTestCase testCaseAt, boolean async) {
        DefaultPropertyExpansionContext properties = (DefaultPropertyExpansionContext) getRunContext().getProperties();
        properties.put("#TestSuiteRunner#", this);

        TestCaseRunner currentRunner = testCaseAt.run(properties, true);
        activeRunners.add(currentRunner);
        if (!async) {
            currentRunner.waitUntilFinished();
        }

        return currentRunner;
    }

    protected void internalFinally(WsdlTestSuiteRunContext runContext) {
        synchronized (lock) {
            WsdlTestSuite testSuite = getTestRunnable();

            try {
                testSuite.runTearDownScript(runContext, this);
            } catch (Exception e) {
                SoapUI.logError(e);
            }

            notifyAfterRun();

            runContext.clear();
            listeners = null;
        }
    }

    private void notifyAfterRun() {
        synchronized (lock) {
            if (listeners == null || listeners.length == 0) {
                return;
            }

            for (int i = 0; i < listeners.length; i++) {
                listeners[i].afterRun(this, getRunContext());
            }
        }
    }

    private void notifyBeforeRun() {
        synchronized (lock) {
            if (listeners == null || listeners.length == 0) {
                return;
            }

            for (int i = 0; i < listeners.length; i++) {
                listeners[i].beforeRun(this, getRunContext());
            }
        }
    }

    private void notifyAfterRunTestCase(TestCaseRunner testCaseRunner) {
        synchronized (lock) {
            if (listeners == null || listeners.length == 0) {
                return;
            }

            for (int i = 0; i < listeners.length; i++) {
                listeners[i].afterTestCase(this, getRunContext(), testCaseRunner);
            }
        }
    }

    private void notifyBeforeRunTestCase(TestCase testCase) {
        synchronized (lock) {
            if (listeners == null || listeners.length == 0) {
                return;
            }

            for (int i = 0; i < listeners.length; i++) {
                listeners[i].beforeTestCase(this, getRunContext(), testCase);
            }
        }
    }

    public TestSuite getTestSuite() {
        return getTestRunnable();
    }

    public List<TestCaseRunner> getResults() {
        synchronized (lock) {
            return Arrays.asList(finishedRunners.toArray(new TestCaseRunner[finishedRunners.size()]));
        }
    }

    public int getCurrentTestCaseIndex() {
        synchronized (lock) {
            return currentTestCaseIndex;
        }
    }

    public WsdlTestCase getCurrentTestCase() {
        synchronized (lock) {
            return currentTestCase;
        }
    }

    private class ParallellTestRunListener extends TestRunListenerAdapter {
        @Override
        public void afterRun(TestCaseRunner testRunner, TestCaseRunContext runContext) {
            try {
                while (!running) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException cause) {
                        cause.printStackTrace();
                    }
                }
                synchronized (lock) {
                    notifyAfterRunTestCase(testRunner);

                    finishedRunners.add(testRunner);

                    testRunner.getTestCase().removeTestRunListener(parallellTestRunListener);

                }
            } finally {
                runCount.decrementAndGet();
                if (runCount.get() == 0) {
                    updateStatus();
                }
            }
        }
    }
}
