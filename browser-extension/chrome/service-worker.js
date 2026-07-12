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

const PROTOCOL_VERSION = "1.3";
const MAX_BODY_CHARS = 2 * 1024 * 1024;
const NETWORK_TOTAL_BUFFER_BYTES = 48 * 1024 * 1024;
const NETWORK_RESOURCE_BUFFER_BYTES = 24 * 1024 * 1024;
const MAX_VISIBLE_REQUESTS = 1000;

let recording = null;

chrome.runtime.onInstalled.addListener(() => {
  chrome.sidePanel.setPanelBehavior({openPanelOnActionClick: true}).catch(() => {});
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  handleMessage(message)
    .then(sendResponse)
    .catch(error => sendResponse({ok: false, error: errorMessage(error)}));
  return true;
});

chrome.debugger.onEvent.addListener((source, method, params) => {
  if (!recording || source.tabId !== recording.tabId) {
    return;
  }
  const task = handleDebuggerEvent(source, method, params).catch(error => {
    notify("recorder-warning", {message: errorMessage(error)});
  });
  trackTask(task);
});

chrome.debugger.onDetach.addListener((source, reason) => {
  if (!recording || source.tabId !== recording.tabId || recording.stopping) {
    return;
  }
  const interruptedRecording = recording;
  recording.attached = false;
  recording.detachReason = reason;
  setRecordingBadge(false);
  if (reason === "target_closed") {
    notify("recorder-detached", {reason, status: recorderStatus()});
    return;
  }
  recording.reconnecting = true;
  notify("recorder-reconnecting", {reason, status: recorderStatus()});
  const task = reconnectDebugger(interruptedRecording, reason);
  trackTask(task);
});

async function handleMessage(message) {
  switch (message?.type) {
    case "start-recording":
      return startRecording(message);
    case "new-transaction":
      return startTransaction(message.name);
    case "stop-recording":
      return stopRecording();
    case "recorder-status":
      return recorderStatus();
    default:
      return {ok: false, error: "Unknown recorder command"};
  }
}

async function startRecording(message) {
  let {tabId} = message;
  const transactionName = String(message.transactionName || "").trim();
  if (!transactionName) {
    throw new Error("Enter a transaction name");
  }
  const startUrl = normalizeStartUrl(message.startUrl);
  const createBlankTab = message.createBlankTab === true;
  let tab;
  if (createBlankTab) {
    tab = await chrome.tabs.create({
      windowId: Number.isInteger(message.windowId) ? message.windowId : undefined,
      url: "about:blank",
      active: true
    });
    tabId = tab.id;
  } else {
    if (!Number.isInteger(tabId)) {
      throw new Error("No active browser tab was selected");
    }
    tab = await chrome.tabs.get(tabId);
  }
  if (isBrowserNewTab(tab.url)) {
    await chrome.tabs.update(tabId, {url: "about:blank"});
    tab = await chrome.tabs.get(tabId);
  }
  if (!createBlankTab && !isRecordableUrl(tab.url)) {
    throw new Error(
      "This Chrome internal page cannot be recorded. Open a New Tab or an HTTP/HTTPS page."
    );
  }
  if (recording) {
    await discardCurrentRecording();
  }

  recording = {
    tabId,
    tabTitle: tab.title || "",
    incognito: Boolean(tab.incognito),
    disableCache: message.disableCache === true,
    startedAt: Date.now(),
    attached: false,
    stopping: false,
    transactions: [],
    currentTransaction: null,
    activeRequests: new Map(),
    requestSequences: new Map(),
    entries: [],
    summaries: [],
    pendingTasks: new Set(),
    reconnecting: false,
    detachReason: null,
    capturedBodyChars: 0,
    nextEntryOrdinal: 0
  };
  startTransactionInternal(transactionName);

  try {
    await chrome.debugger.attach({tabId}, PROTOCOL_VERSION);
    recording.attached = true;
    await configureRootTarget(tabId);
    if (startUrl) {
      const navigation = await chrome.debugger.sendCommand({tabId}, "Page.navigate", {url: startUrl});
      if (navigation?.errorText) {
        throw new Error(`Unable to navigate to ${startUrl}: ${navigation.errorText}`);
      }
    }
  } catch (error) {
    await discardCurrentRecording();
    throw error;
  }

  setRecordingBadge(true);
  notify("recording-started", recorderStatus());
  return recorderStatus();
}

async function configureRootTarget(tabId) {
  await enableTarget({tabId});
  await safeCommand({tabId}, "Target.setAutoAttach", {
    autoAttach: true,
    waitForDebuggerOnStart: false,
    flatten: true,
    filter: [
      {type: "iframe", exclude: false},
      {type: "worker", exclude: false},
      {type: "shared_worker", exclude: false},
      {type: "service_worker", exclude: false}
    ]
  });
}

async function reconnectDebugger(interruptedRecording, reason) {
  let lastError = null;
  for (let attempt = 0; attempt < 12; attempt++) {
    if (recording !== interruptedRecording || recording.stopping) {
      return;
    }
    await delay(attempt === 0 ? 100 : 500);
    try {
      await chrome.debugger.attach({tabId: interruptedRecording.tabId}, PROTOCOL_VERSION);
      interruptedRecording.attached = true;
      await configureRootTarget(interruptedRecording.tabId);
      interruptedRecording.reconnecting = false;
      interruptedRecording.detachReason = null;
      setRecordingBadge(true);
      notify("recorder-reattached", {reason, status: recorderStatus()});
      return;
    } catch (error) {
      lastError = error;
      interruptedRecording.attached = false;
    }
  }
  if (recording === interruptedRecording && !recording.stopping) {
    recording.reconnecting = false;
    notify("recorder-detached", {
      reason,
      message: errorMessage(lastError),
      status: recorderStatus()
    });
  }
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

async function enableTarget(target) {
  await chrome.debugger.sendCommand(target, "Network.enable", {
    maxTotalBufferSize: NETWORK_TOTAL_BUFFER_BYTES,
    maxResourceBufferSize: NETWORK_RESOURCE_BUFFER_BYTES,
    maxPostDataSize: MAX_BODY_CHARS
  });
  await safeCommand(target, "Network.configureDurableMessages", {
    maxTotalBufferSize: NETWORK_TOTAL_BUFFER_BYTES,
    maxResourceBufferSize: NETWORK_RESOURCE_BUFFER_BYTES
  });
  if (recording?.disableCache) {
    await chrome.debugger.sendCommand(target, "Network.setCacheDisabled", {cacheDisabled: true});
  }
}

async function startTransaction(name) {
  if (!recording || recording.stopping) {
    throw new Error("No recording is active");
  }
  const normalizedName = String(name || "").trim();
  if (!normalizedName) {
    throw new Error("Enter a transaction name");
  }
  if (recording.currentTransaction?.name === normalizedName) {
    return {ok: true, transaction: recording.currentTransaction, status: recorderStatus()};
  }
  const transaction = startTransactionInternal(normalizedName);
  notify("transaction-started", {transaction});
  return {ok: true, transaction, status: recorderStatus()};
}

function startTransactionInternal(name) {
  const normalizedName = String(name || "").trim();
  if (!normalizedName) {
    throw new Error("Enter a transaction name");
  }
  const transaction = {
    id: `transaction-${recording.transactions.length + 1}`,
    name: normalizedName,
    startedDateTime: new Date().toISOString(),
    order: recording.transactions.length + 1,
    requestCount: 0
  };
  recording.transactions.push(transaction);
  recording.currentTransaction = transaction;
  return transaction;
}

async function stopRecording() {
  if (!recording) {
    throw new Error("No recording is active");
  }
  recording.stopping = true;
  await settlePendingTasks();

  for (const state of recording.activeRequests.values()) {
    state.incomplete = true;
    finalizeRequest(state, state.lastTimestamp || state.requestTimestamp);
  }
  recording.activeRequests.clear();

  if (recording.attached) {
    try {
      await chrome.debugger.detach({tabId: recording.tabId});
    } catch (_ignored) {
      // The tab or debugger session may already have gone away.
    }
  }
  recording.attached = false;
  setRecordingBadge(false);

  const har = buildHar(recording);
  const status = recorderStatus();
  recording = null;
  notify("recording-stopped", status);
  return {ok: true, har, status};
}

async function discardCurrentRecording() {
  if (!recording) {
    return;
  }
  recording.stopping = true;
  if (recording.attached) {
    try {
      await chrome.debugger.detach({tabId: recording.tabId});
    } catch (_ignored) {
      // Ignore cleanup failure when replacing a stale session.
    }
  }
  setRecordingBadge(false);
  recording = null;
}

async function settlePendingTasks() {
  for (let pass = 0; pass < 4; pass++) {
    const tasks = [...recording.pendingTasks];
    if (tasks.length === 0) {
      return;
    }
    await Promise.allSettled(tasks);
  }
}

function trackTask(task) {
  if (!recording) {
    return;
  }
  const owner = recording;
  owner.pendingTasks.add(task);
  task.finally(() => owner.pendingTasks.delete(task));
}

async function handleDebuggerEvent(source, method, params) {
  switch (method) {
    case "Target.attachedToTarget": {
      const child = {tabId: source.tabId, sessionId: params.sessionId};
      await enableTarget(child);
      await safeCommand(child, "Target.setAutoAttach", {
        autoAttach: true,
        waitForDebuggerOnStart: false,
        flatten: true
      });
      break;
    }
    case "Network.requestWillBeSent":
      await requestWillBeSent(source, params);
      break;
    case "Network.requestWillBeSentExtraInfo":
      requestExtraInfo(source, params);
      break;
    case "Network.responseReceived":
      responseReceived(source, params);
      break;
    case "Network.responseReceivedExtraInfo":
      responseExtraInfo(source, params);
      break;
    case "Network.loadingFinished":
      await loadingFinished(source, params);
      break;
    case "Network.loadingFailed":
      loadingFailed(source, params);
      break;
    default:
      break;
  }
}

async function requestWillBeSent(source, params) {
  const key = requestKey(source, params.requestId);
  const previous = recording.activeRequests.get(key);
  if (params.redirectResponse && previous) {
    applyResponse(previous, params.redirectResponse, params.timestamp);
    previous.redirect = true;
    recording.activeRequests.delete(key);
    const owner = recording;
    const redirectTask = captureRedirectBody(owner, previous, source)
      .finally(() => {
        if (recording === owner) {
          finalizeRequest(previous, params.timestamp);
        }
      });
    trackTask(redirectTask);
  }

  const request = params.request || {};
  if (!isCapturableRequestUrl(request.url)) {
    return;
  }
  const sequence = (recording.requestSequences.get(key) || 0) + 1;
  recording.requestSequences.set(key, sequence);
  const transaction = recording.currentTransaction;
  const wallTimeMs = Number.isFinite(params.wallTime) ? params.wallTime * 1000 : Date.now();
  const state = {
    key,
    entryId: `${key}:${sequence}`,
    ordinal: recording.nextEntryOrdinal++,
    source: {...source},
    requestId: params.requestId,
    requestTimestamp: params.timestamp,
    lastTimestamp: params.timestamp,
    startedDateTime: new Date(wallTimeMs).toISOString(),
    resourceType: params.type || "Other",
    initiator: params.initiator || {},
    transaction: {...transaction},
    request: {
      method: request.method || "GET",
      url: request.url || "",
      httpVersion: "",
      headers: headersToHar(request.headers),
      queryString: queryToHar(request.url),
      cookies: [],
      headersSize: -1,
      bodySize: request.postData ? request.postData.length : 0
    },
    response: emptyResponse(),
    responseTimestamp: null,
    encodedDataLength: 0,
    bodyTruncated: false,
    failed: false,
    incomplete: false
  };
  const activeTransaction = recording.transactions.find(item => item.id === transaction.id);
  if (activeTransaction) {
    activeTransaction.requestCount++;
  }
  const postData = makePostData(request.postData, state.request.headers);
  if (postData) {
    state.request.postData = postData;
  }
  recording.activeRequests.set(key, state);

  if (request.hasPostData && typeof request.postData !== "string") {
    state.postDataTask = chrome.debugger.sendCommand(source, "Network.getRequestPostData", {
      requestId: params.requestId
    }).then(result => {
      const current = recording?.activeRequests.get(key);
      if (current === state && typeof result?.postData === "string") {
        current.request.postData = makePostData(result.postData, current.request.headers);
        current.request.bodySize = result.postData.length;
      }
    }).catch(() => {
      state.requestBodyUnavailable = true;
    });
    await state.postDataTask;
  }
}

async function captureRedirectBody(owner, state, source) {
  try {
    const result = await chrome.debugger.sendCommand(source, "Network.getResponseBody", {
      requestId: state.requestId
    });
    if (recording === owner) {
      captureBody(state, result);
    }
  } catch (error) {
    if (responseMayHaveBody(state)) {
      state.bodyUnavailable = true;
      state.bodyUnavailableReason = `Redirect body unavailable: ${errorMessage(error)}`;
    }
  }
}

function responseMayHaveBody(state) {
  if (state.request.method === "HEAD" || state.response.status === 204 || state.response.status === 304) {
    return false;
  }
  const contentLength = Number.parseInt(headerValue(state.response.headers, "content-length"), 10);
  return contentLength > 0 || Boolean(headerValue(state.response.headers, "transfer-encoding"));
}

function requestExtraInfo(source, params) {
  const state = recording.activeRequests.get(requestKey(source, params.requestId));
  if (state && params.headers) {
    state.request.headers = headersToHar(params.headers);
  }
}

function responseReceived(source, params) {
  const state = recording.activeRequests.get(requestKey(source, params.requestId));
  if (!state) {
    return;
  }
  applyResponse(state, params.response || {}, params.timestamp);
  state.resourceType = params.type || state.resourceType;
}

function responseExtraInfo(source, params) {
  const state = recording.activeRequests.get(requestKey(source, params.requestId));
  if (!state) {
    return;
  }
  if (params.headers) {
    state.response.headers = headersToHar(params.headers);
  }
  if (Number.isInteger(params.statusCode)) {
    state.response.status = params.statusCode;
  }
}

async function loadingFinished(source, params) {
  const key = requestKey(source, params.requestId);
  const state = recording.activeRequests.get(key);
  if (!state) {
    return;
  }
  state.lastTimestamp = params.timestamp;
  state.encodedDataLength = params.encodedDataLength || 0;
  state.response.bodySize = state.encodedDataLength;
  if (state.postDataTask) {
    await state.postDataTask;
  }

  if (!state.redirect) {
    try {
      const result = await getResponseBodyWithRetry(source, params.requestId);
      captureBody(state, result);
    } catch (error) {
      state.bodyUnavailable = true;
      state.bodyUnavailableReason = errorMessage(error);
    }
  }
  finalizeRequest(state, params.timestamp);
  recording.activeRequests.delete(key);
}

async function getResponseBodyWithRetry(source, requestId) {
  let lastError = null;
  for (const waitMs of [0, 50, 150]) {
    if (waitMs > 0) {
      await delay(waitMs);
    }
    try {
      return await chrome.debugger.sendCommand(source, "Network.getResponseBody", {requestId});
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError || new Error("Chrome did not make the response body available");
}

function loadingFailed(source, params) {
  const key = requestKey(source, params.requestId);
  const state = recording.activeRequests.get(key);
  if (!state) {
    return;
  }
  state.failed = true;
  state.failureText = params.errorText || "Request failed";
  state.canceled = Boolean(params.canceled);
  state.lastTimestamp = params.timestamp;
  finalizeRequest(state, params.timestamp);
  recording.activeRequests.delete(key);
}

function applyResponse(state, response, timestamp) {
  state.responseTimestamp = timestamp;
  state.lastTimestamp = timestamp;
  state.response = {
    status: response.status || 0,
    statusText: response.statusText || "",
    httpVersion: response.protocol || "",
    headers: headersToHar(response.headers),
    cookies: [],
    content: {
      size: Number.isFinite(response.encodedDataLength) ? response.encodedDataLength : 0,
      mimeType: response.mimeType || headerValue(headersToHar(response.headers), "content-type") || ""
    },
    redirectURL: headerValue(headersToHar(response.headers), "location") || "",
    headersSize: -1,
    bodySize: Number.isFinite(response.encodedDataLength) ? response.encodedDataLength : -1,
    _transferSize: Number.isFinite(response.encodedDataLength) ? response.encodedDataLength : 0
  };
  state.serverIPAddress = response.remoteIPAddress || "";
  state.connection = response.connectionId == null ? "" : String(response.connectionId);
  state.fromDiskCache = Boolean(response.fromDiskCache);
  state.fromServiceWorker = Boolean(response.fromServiceWorker);
  state.timing = response.timing || null;
}

function captureBody(state, result) {
  if (!result || typeof result.body !== "string") {
    state.bodyUnavailable = true;
    state.bodyUnavailableReason = "Chrome returned no response body";
    return;
  }
  const available = MAX_BODY_CHARS;
  const safeAvailable = result.base64Encoded ? available - (available % 4) : available;
  if (safeAvailable <= 0) {
    state.bodyTruncated = true;
    return;
  }
  const body = result.body.slice(0, safeAvailable);
  state.bodyTruncated = result.body.length > body.length;
  state.response.content.text = body;
  if (result.base64Encoded) {
    state.response.content.encoding = "base64";
  }
  recording.capturedBodyChars += body.length;
}

function finalizeRequest(state, finishedTimestamp) {
  if (state.finalized) {
    return;
  }
  state.finalized = true;
  const end = Number.isFinite(finishedTimestamp) ? finishedTimestamp : state.requestTimestamp;
  const totalMs = Math.max((end - state.requestTimestamp) * 1000, 0);
  const entry = {
    startedDateTime: state.startedDateTime,
    time: totalMs,
    request: state.request,
    response: state.response,
    cache: {},
    timings: buildTimings(state, totalMs),
    serverIPAddress: state.serverIPAddress || "",
    connection: state.connection || "",
    _resourceType: state.resourceType,
    _fromCache: state.fromDiskCache ? "disk" : (state.fromServiceWorker ? "service-worker" : undefined),
    _breaktest: {
      transactionId: state.transaction.id,
      transactionName: state.transaction.name,
      transactionOrder: state.transaction.order,
      entryOrdinal: state.ordinal,
      bodyTruncated: state.bodyTruncated,
      bodyUnavailable: Boolean(state.bodyUnavailable),
      bodyUnavailableReason: state.bodyUnavailableReason || "",
      requestBodyUnavailable: Boolean(state.requestBodyUnavailable),
      failed: state.failed,
      incomplete: state.incomplete,
      failureText: state.failureText || ""
    }
  };
  if (!entry._fromCache) {
    delete entry._fromCache;
  }
  recording.entries.push(entry);

  const summary = {
    id: state.entryId,
    transactionId: state.transaction.id,
    transactionName: state.transaction.name,
    method: state.request.method,
    url: state.request.url,
    status: state.response.status,
    resourceType: state.resourceType,
    size: state.response.bodySize,
    failed: state.failed,
    bodyTruncated: state.bodyTruncated,
    bodyUnavailable: Boolean(state.bodyUnavailable)
  };
  recording.summaries.push(summary);
  if (recording.summaries.length > MAX_VISIBLE_REQUESTS) {
    recording.summaries.shift();
  }
  notify("request-finished", {summary, status: recorderStatus()});
}

function buildHar(session) {
  const entries = [...session.entries].sort((left, right) => {
    const timeDifference = Date.parse(left.startedDateTime) - Date.parse(right.startedDateTime);
    return timeDifference || left._breaktest.entryOrdinal - right._breaktest.entryOrdinal;
  });
  return {
    log: {
      version: "1.2",
      creator: {
        name: "BreakTest Browser Recorder",
        version: chrome.runtime.getManifest().version
      },
      pages: [],
      entries,
      _breaktest: {
        formatVersion: 1,
        recordedWith: "chrome.debugger",
        startedDateTime: new Date(session.startedAt).toISOString(),
        tabTitle: session.tabTitle,
        transactions: session.transactions
      }
    }
  };
}

function buildTimings(state, totalMs) {
  const timing = state.timing;
  if (!timing) {
    return {blocked: 0, dns: -1, connect: -1, ssl: -1, send: 0, wait: totalMs, receive: 0};
  }
  const dns = duration(timing.dnsStart, timing.dnsEnd);
  const connect = duration(timing.connectStart, timing.connectEnd);
  const ssl = duration(timing.sslStart, timing.sslEnd);
  const send = duration(timing.sendStart, timing.sendEnd, 0);
  const wait = duration(timing.sendEnd, timing.receiveHeadersEnd, 0);
  const known = [dns, connect, send, wait].filter(value => value > 0).reduce((sum, value) => sum + value, 0);
  const receive = Math.max(totalMs - known, 0);
  return {blocked: 0, dns, connect, ssl, send, wait, receive};
}

function duration(start, end, unavailable = -1) {
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end < 0) {
    return unavailable;
  }
  return Math.max(end - start, 0);
}

function emptyResponse() {
  return {
    status: 0,
    statusText: "",
    httpVersion: "",
    headers: [],
    cookies: [],
    content: {size: 0, mimeType: ""},
    redirectURL: "",
    headersSize: -1,
    bodySize: -1
  };
}

function headersToHar(headers) {
  if (!headers || typeof headers !== "object") {
    return [];
  }
  const result = [];
  for (const [name, rawValue] of Object.entries(headers)) {
    const values = String(rawValue).split("\n");
    for (const value of values) {
      result.push({name, value});
    }
  }
  return result;
}

function headerValue(headers, wantedName) {
  const header = headers.find(item => item.name.toLowerCase() === wantedName.toLowerCase());
  return header ? header.value : "";
}

function queryToHar(url) {
  try {
    return [...new URL(url).searchParams.entries()].map(([name, value]) => ({name, value}));
  } catch (_ignored) {
    return [];
  }
}

function makePostData(text, headers) {
  if (typeof text !== "string") {
    return null;
  }
  const mimeType = headerValue(headers, "content-type");
  const postData = {mimeType, text, params: []};
  if (mimeType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
    postData.params = [...new URLSearchParams(text).entries()].map(([name, value]) => ({name, value}));
  }
  return postData;
}

function requestKey(source, requestId) {
  return `${source.sessionId || "root"}:${requestId}`;
}

function recorderStatus() {
  if (!recording) {
    return {ok: true, active: false, summaries: []};
  }
  return {
    ok: true,
    active: !recording.stopping,
    attached: recording.attached,
    reconnecting: recording.reconnecting,
    detachReason: recording.detachReason,
    tabId: recording.tabId,
    currentTransaction: recording.currentTransaction,
    transactions: recording.transactions,
    requestCount: recording.entries.length,
    pendingCount: recording.activeRequests.size,
    capturedBodyChars: recording.capturedBodyChars,
    incognito: recording.incognito,
    cacheDisabled: recording.disableCache,
    summaries: recording.summaries
  };
}

function isRecordableUrl(url) {
  return typeof url === "string" && (/^(https?|file):/i.test(url) || url === "about:blank");
}

function isCapturableRequestUrl(url) {
  return typeof url === "string" && /^https?:/i.test(url);
}

function normalizeStartUrl(value) {
  const text = String(value || "").trim();
  if (!text) {
    return "";
  }
  let candidate = text;
  if (!/^[a-z][a-z0-9+.-]*:/i.test(candidate)) {
    candidate = /^(localhost|127\.0\.0\.1|\[::1\])(?::|\/|$)/i.test(candidate)
      ? `http://${candidate}`
      : `https://${candidate}`;
  }
  let parsed;
  try {
    parsed = new URL(candidate);
  } catch (_ignored) {
    throw new Error("Enter a valid HTTP or HTTPS start URL");
  }
  if (!/^https?:$/.test(parsed.protocol)) {
    throw new Error("The start URL must use HTTP or HTTPS");
  }
  return parsed.href;
}

function isBrowserNewTab(url) {
  if (typeof url !== "string") {
    return false;
  }
  return url === "about:blank"
    || /^(chrome|edge):\/\/(newtab|new-tab-page)\/?/i.test(url)
    || url.startsWith("chrome-search://");
}

async function safeCommand(target, method, params) {
  try {
    return await chrome.debugger.sendCommand(target, method, params);
  } catch (_ignored) {
    return null;
  }
}

function setRecordingBadge(active) {
  chrome.action.setBadgeText({text: active ? "REC" : ""});
  if (active) {
    chrome.action.setBadgeBackgroundColor({color: "#c62828"});
  }
}

function notify(type, payload) {
  chrome.runtime.sendMessage({type, ...payload}).catch(() => {
    // The side panel can be closed while recording continues.
  });
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}
