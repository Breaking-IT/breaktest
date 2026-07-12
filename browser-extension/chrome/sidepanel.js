/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const transactionName = document.querySelector("#transaction-name");
const startUrl = document.querySelector("#start-url");
const disableCache = document.querySelector("#disable-cache");
const startSetup = document.querySelector("#start-setup");
const startActions = document.querySelector("#start-actions");
const startButton = document.querySelector("#start-button");
const blankStartButton = document.querySelector("#blank-start-button");
const incognitoStartButton = document.querySelector("#incognito-start-button");
const stopButton = document.querySelector("#stop-button");
const transactionHint = document.querySelector("#transaction-hint");
const clearViewButton = document.querySelector("#clear-view-button");
const statusText = document.querySelector("#status-text");
const indicator = document.querySelector("#recording-indicator");
const requestCount = document.querySelector("#request-count");
const pendingCount = document.querySelector("#pending-count");
const errorCount = document.querySelector("#error-count");
const transactionList = document.querySelector("#transaction-list");
const requestList = document.querySelector("#request-list");

let active = false;
let busy = false;
let errors = 0;
let lastCommittedTransactionName = transactionName.value.trim();
let transactionNameDirty = false;
let lastTransactionInputAt = 0;
let transactionCommitTimer = null;
let transactionCommitPromise = Promise.resolve();
const TRANSACTION_TYPING_SETTLE_MS = 250;
const INCOGNITO_LAUNCH_KEY = "pendingIncognitoLaunch";
const INCOGNITO_LAUNCH_TTL_MS = 60_000;
const inIncognitoContext = chrome.extension.inIncognitoContext;

startButton.addEventListener("click", () => startRecording(false));
blankStartButton.addEventListener("click", () => startRecording(true));
incognitoStartButton.addEventListener("click", startIncognitoRecording);
stopButton.addEventListener("click", finishRecording);
clearViewButton.addEventListener("click", () => {
  requestList.replaceChildren();
  errors = 0;
  errorCount.textContent = "0";
});
transactionName.addEventListener("keydown", event => {
  if (event.key === "Enter") {
    event.preventDefault();
    active ? commitPendingTransaction() : startRecording(true);
  }
});
transactionName.addEventListener("input", () => {
  transactionNameDirty = transactionName.value.trim() !== lastCommittedTransactionName;
  lastTransactionInputAt = Date.now();
  clearTimeout(transactionCommitTimer);
});
transactionName.addEventListener("blur", () => scheduleTransactionCommit(true));
document.addEventListener("pointermove", () => scheduleTransactionCommit(false));

chrome.runtime.onMessage.addListener(message => {
  switch (message.type) {
    case "request-finished":
      appendRequest(message.summary);
      applyStatus(message.status);
      break;
    case "transaction-started":
      renderTransactions();
      break;
    case "recorder-detached":
      applyStatus(message.status || {active: true, attached: false});
      showError(message.message
        ? `Recorder interrupted: ${message.message}`
        : `Recorder interrupted: ${message.reason}. Captured requests can still be exported.`);
      break;
    case "recorder-reconnecting":
      applyStatus(message.status);
      setStatus("Recorder connection interrupted. Reconnecting…");
      break;
    case "recorder-reattached":
      applyStatus(message.status);
      setStatus("Recorder reconnected. Requests are being captured again.");
      break;
    case "recorder-warning":
      setStatus(message.message);
      break;
    default:
      break;
  }
});

initialize();

async function initialize() {
  incognitoStartButton.hidden = inIncognitoContext;
  if (await claimPendingIncognitoLaunch()) {
    return;
  }
  await removeExpiredIncognitoLaunch();
  await restoreStatus();
}

async function claimPendingIncognitoLaunch() {
  if (!inIncognitoContext) {
    return false;
  }
  try {
    const currentWindow = await chrome.windows.getCurrent();
    const stored = await chrome.storage.local.get(INCOGNITO_LAUNCH_KEY);
    const launch = stored[INCOGNITO_LAUNCH_KEY];
    if (!isValidIncognitoLaunch(launch) || launch.windowId !== currentWindow.id) {
      return false;
    }
    await chrome.storage.local.remove(INCOGNITO_LAUNCH_KEY);
    startUrl.value = launch.startUrl;
    disableCache.checked = launch.disableCache === true;
    transactionName.value = launch.transactionName;
    lastCommittedTransactionName = launch.transactionName.trim();
    transactionNameDirty = false;
    const started = await startRecording(true);
    if (!started) {
      await restoreStatus();
    } else if (Number.isInteger(launch.launcherTabId)) {
      await chrome.tabs.remove(launch.launcherTabId).catch(() => {});
    }
    return true;
  } catch (error) {
    showError(`Unable to start the incognito recorder: ${error.message}`);
    return false;
  }
}

async function removeExpiredIncognitoLaunch() {
  try {
    const stored = await chrome.storage.local.get(INCOGNITO_LAUNCH_KEY);
    if (!isValidIncognitoLaunch(stored[INCOGNITO_LAUNCH_KEY])) {
      await chrome.storage.local.remove(INCOGNITO_LAUNCH_KEY);
    }
  } catch (_ignored) {
    // A stale launch instruction is harmless and will be replaced on the next attempt.
  }
}

function isValidIncognitoLaunch(launch) {
  return launch
    && Number.isInteger(launch.windowId)
    && typeof launch.transactionName === "string"
    && typeof launch.startUrl === "string"
    && Number.isFinite(launch.createdAt)
    && Date.now() - launch.createdAt <= INCOGNITO_LAUNCH_TTL_MS;
}

async function startIncognitoRecording() {
  setBusy(true);
  let launch;
  try {
    if (inIncognitoContext) {
      throw new Error("This recorder is already running in an incognito window");
    }
    if (!transactionName.value.trim()) {
      throw new Error("Enter a transaction name");
    }
    const allowed = await chrome.extension.isAllowedIncognitoAccess();
    if (!allowed) {
      throw new Error("Enable Allow in incognito in the extension Details page, then try again");
    }
    launch = {
      nonce: crypto.randomUUID(),
      transactionName: transactionName.value.trim(),
      startUrl: startUrl.value.trim(),
      disableCache: disableCache.checked,
      createdAt: Date.now()
    };
    await chrome.storage.local.set({[INCOGNITO_LAUNCH_KEY]: launch});
    const launcherUrl = new URL(chrome.runtime.getURL("incognito-launch.html"));
    launcherUrl.searchParams.set("launch", launch.nonce);
    const incognitoWindow = await chrome.windows.create({
      incognito: true,
      focused: true,
      url: launcherUrl.href
    });
    if (Number.isInteger(incognitoWindow?.id)) {
      const stored = await chrome.storage.local.get(INCOGNITO_LAUNCH_KEY);
      const launcherReadyLaunch = stored[INCOGNITO_LAUNCH_KEY]?.nonce === launch.nonce
        ? stored[INCOGNITO_LAUNCH_KEY]
        : launch;
      launch = {
        ...launcherReadyLaunch,
        windowId: incognitoWindow.id,
        launcherTabId: incognitoWindow.tabs?.[0]?.id ?? launcherReadyLaunch.launcherTabId
      };
      await chrome.storage.local.set({[INCOGNITO_LAUNCH_KEY]: launch});
      try {
        await chrome.sidePanel.open({windowId: incognitoWindow.id});
        setStatus("Incognito recorder opened. Recording will start in that window.");
      } catch (_ignored) {
        setStatus("Incognito window opened. Choose Open recorder on the BreakTest page there.");
      }
    } else {
      setStatus("Incognito window opened. Choose Open recorder on the BreakTest page there.");
    }
    setTimeout(() => removeLaunchIfUnclaimed(launch.nonce), INCOGNITO_LAUNCH_TTL_MS);
  } catch (error) {
    if (launch?.nonce) {
      await removeLaunchIfUnclaimed(launch.nonce);
    }
    showError(error.message);
  } finally {
    setBusy(false);
  }
}

async function removeLaunchIfUnclaimed(nonce) {
  try {
    const stored = await chrome.storage.local.get(INCOGNITO_LAUNCH_KEY);
    if (stored[INCOGNITO_LAUNCH_KEY]?.nonce === nonce) {
      await chrome.storage.local.remove(INCOGNITO_LAUNCH_KEY);
    }
  } catch (_ignored) {
    // Cleanup is best effort; a timestamp also prevents stale instructions from being used.
  }
}

async function restoreStatus() {
  const response = await send({type: "recorder-status"});
  if (!response.ok) {
    showError(response.error);
    return;
  }
  applyStatus(response);
  if (!response.active) {
    requestList.replaceChildren();
    transactionList.replaceChildren();
    errors = 0;
    errorCount.textContent = "0";
  }
  if (response.summaries) {
    for (const summary of response.summaries) {
      appendRequest(summary, false);
    }
  }
}

async function startRecording(createBlankTab) {
  setBusy(true);
  try {
    const [tab] = await chrome.tabs.query({active: true, lastFocusedWindow: true});
    const response = await send({
      type: "start-recording",
      tabId: tab?.id,
      windowId: tab?.windowId,
      transactionName: transactionName.value,
      startUrl: startUrl.value,
      createBlankTab,
      disableCache: disableCache.checked
    });
    if (!response.ok) {
      throw new Error(response.error);
    }
    requestList.replaceChildren();
    errors = 0;
    errorCount.textContent = "0";
    applyStatus(response);
    setStatus(startUrl.value.trim()
      ? `${response.incognito ? "Incognito recording" : "Recording"} started and the start URL was opened.`
      : (createBlankTab
        ? `${response.incognito ? "Incognito recording" : "Recording"} started in a blank tab.`
        : `${response.incognito ? "Incognito recording" : "Recording"} the selected tab.`));
    return true;
  } catch (error) {
    showError(error.message);
    return false;
  } finally {
    setBusy(false);
  }
}

function scheduleTransactionCommit(force) {
  if (!active || !transactionNameDirty) {
    return;
  }
  clearTimeout(transactionCommitTimer);
  const elapsed = Date.now() - lastTransactionInputAt;
  const delay = force ? 0 : Math.max(0, TRANSACTION_TYPING_SETTLE_MS - elapsed);
  transactionCommitTimer = setTimeout(() => commitPendingTransaction(), delay);
}

function commitPendingTransaction() {
  clearTimeout(transactionCommitTimer);
  transactionCommitPromise = transactionCommitPromise.then(async () => {
    if (!active || !transactionNameDirty) {
      return;
    }
    const name = transactionName.value.trim();
    if (!name) {
      showError("Enter a transaction name");
      return;
    }
    if (name === lastCommittedTransactionName) {
      transactionNameDirty = false;
      return;
    }
    const response = await send({type: "new-transaction", name});
    if (!response.ok) {
      showError(response.error);
      return;
    }
    lastCommittedTransactionName = response.transaction.name;
    transactionNameDirty = transactionName.value.trim() !== lastCommittedTransactionName;
    applyStatus(response.status);
    setStatus(`Recording transaction: ${lastCommittedTransactionName}`);
  });
  return transactionCommitPromise;
}

async function finishRecording() {
  setBusy(true);
  setStatus("Finishing pending requests and building HAR…");
  try {
    await commitPendingTransaction();
    const response = await send({type: "stop-recording"});
    if (!response.ok) {
      throw new Error(response.error);
    }
    downloadHar(response.har);
    applyStatus({active: false, requestCount: response.har.log.entries.length, pendingCount: 0});
    setStatus("HAR exported. Import it with File → Import HAR… in BreakTest.");
  } catch (error) {
    showError(error.message);
  } finally {
    setBusy(false);
  }
}

function downloadHar(har) {
  const json = JSON.stringify(har, null, 2);
  const blob = new Blob([json], {type: "application/json"});
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  const stamp = new Date().toISOString().replaceAll(":", "-").replace(/\.\d{3}Z$/, "Z");
  anchor.href = url;
  anchor.download = `breaktest-recording-${stamp}.har`;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function applyStatus(status) {
  setActive(Boolean(status.active));
  indicator.classList.toggle("active", Boolean(status.active && status.attached !== false));
  indicator.classList.toggle("reconnecting", Boolean(status.reconnecting));
  requestCount.textContent = String(status.requestCount ?? 0);
  pendingCount.textContent = String(status.pendingCount ?? 0);
  if (status.currentTransaction) {
    const currentName = status.currentTransaction.name;
    if (!transactionNameDirty) {
      transactionName.value = currentName;
    }
    lastCommittedTransactionName = currentName;
    transactionNameDirty = transactionName.value.trim() !== lastCommittedTransactionName;
  }
  if (status.transactions) {
    renderTransactions(status.transactions);
  }
}

function setActive(isActive) {
  active = isActive;
  startSetup.hidden = isActive;
  startActions.hidden = isActive;
  startButton.disabled = busy || isActive;
  blankStartButton.disabled = busy || isActive;
  incognitoStartButton.disabled = busy || isActive;
  stopButton.hidden = !isActive;
  stopButton.disabled = busy || !isActive;
  transactionHint.hidden = !isActive;
  transactionName.disabled = false;
  if (!isActive) {
    clearTimeout(transactionCommitTimer);
    transactionNameDirty = false;
  }
  if (!isActive && !statusText.textContent.includes("exported")) {
    setStatus("");
  }
}

function setBusy(isBusy) {
  busy = isBusy;
  startButton.disabled = busy || active;
  blankStartButton.disabled = busy || active;
  incognitoStartButton.disabled = busy || active;
  stopButton.disabled = busy || !active;
}

function renderTransactions(transactions) {
  if (transactions) {
    transactionList.replaceChildren();
    for (const transaction of transactions) {
      const item = document.createElement("li");
      item.textContent = `${transaction.name} (${transaction.requestCount ?? 0})`;
      transactionList.append(item);
    }
  } else {
    send({type: "recorder-status"}).then(response => {
      if (response.ok) {
        renderTransactions(response.transactions || []);
      }
    });
  }
}

function appendRequest(summary, updateErrors = true) {
  const row = document.createElement("article");
  row.className = `request ${summary.failed || summary.status >= 400 ? "failed" : ""}`;

  const status = document.createElement("span");
  status.className = "request-status";
  status.textContent = summary.status || (summary.failed ? "ERR" : "—");
  const method = document.createElement("strong");
  method.textContent = summary.method;

  const path = document.createElement("span");
  path.className = "request-path";
  path.textContent = requestPath(summary.url);
  path.title = `${summary.url}\n${summary.transactionName} · ${summary.resourceType}`;

  row.append(status, method, path);
  if (summary.bodyTruncated || summary.bodyUnavailable) {
    const bodyWarning = document.createElement("span");
    bodyWarning.className = "request-body-warning";
    bodyWarning.textContent = "⚠";
    bodyWarning.title = summary.bodyTruncated ? "Response body truncated" : "Response body unavailable";
    row.append(bodyWarning);
  }

  requestList.prepend(row);
  while (requestList.childElementCount > 300) {
    requestList.lastElementChild.remove();
  }
  if (updateErrors && (summary.failed || summary.status >= 400)) {
    errors++;
    errorCount.textContent = String(errors);
  }
}

function requestPath(url) {
  try {
    const parsed = new URL(url);
    return `${parsed.pathname || "/"}${parsed.search}`;
  } catch (_ignored) {
    return url;
  }
}

function showError(message) {
  setStatus(message || "Unexpected recorder error");
  statusText.classList.add("error");
  setTimeout(() => statusText.classList.remove("error"), 4000);
}

function setStatus(message) {
  statusText.textContent = message;
  statusText.hidden = !message;
}

async function send(message) {
  try {
    return await chrome.runtime.sendMessage(message);
  } catch (error) {
    return {ok: false, error: error.message};
  }
}
