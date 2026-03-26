const AGENTS = [
  {
    key: "index_check",
    name: "指标校核",
    desc: "标准与阈值复核",
    placeholder: "输入指标、标准或阈值，我来帮你做校核与差异说明。",
    category: "work",
    detail:
      "面向测绘生产质量控制环节的指标核对：对照标准/规范/项目要求，给出差异点、风险点与修正建议（原型展示）。",
    tags: ["指标表", "标准/规范", "阈值", "单位/精度", "输出：差异清单"],
  },
  {
    key: "asbuilt_area",
    name: "竣工测量面积计算",
    desc: "面积与报表草稿",
    placeholder: "粘贴坐标/闭合线信息或面积口径，我来生成计算步骤与结果模板。",
    category: "work",
    detail:
      "面向竣工测量的面积计算与成果表达：按口径给出计算流程、结果汇总与报表草稿（原型展示）。",
    tags: ["坐标/界址", "闭合检查", "面积口径", "单位换算", "输出：报表模板"],
  },
  {
    key: "logic_check",
    name: "逻辑性检查",
    desc: "规则与流程审查",
    placeholder: "描述业务流程或规则，我来检查逻辑漏洞与遗漏条件。",
    category: "work",
    detail:
      "面向生产流程与规则配置的逻辑审查：发现遗漏条件、冲突规则、不可达分支等问题并给出修改建议（原型展示）。",
    tags: ["规则集", "流程节点", "边界条件", "冲突检测", "输出：问题清单"],
  },
  {
    key: "consistency_check",
    name: "一致性检查",
    desc: "表格/图纸一致",
    placeholder: "给出两份数据/口径，我来列出不一致点与建议修正。",
    category: "work",
    detail:
      "面向多源成果的一致性核查：对照图、表、库、报告等，定位不一致项并建议统一口径（原型展示）。",
    tags: ["表/图/库", "字段映射", "口径对齐", "差异比对", "输出：修正建议"],
  },
  {
    key: "rag_search",
    name: "RAG检索",
    desc: "基于知识库查找",
    placeholder: "输入关键词或问题，我来返回检索条目与引用位置（原型展示）。",
    category: "work",
    detail:
      "面向测绘知识库的检索增强：返回相关条目、引用片段与来源位置，便于快速定位依据（原型展示）。",
    tags: ["关键词", "召回条目", "引用片段", "来源位置", "输出：检索列表"],
  },
];

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

function setVisible(element, visible) {
  if (!element) return;
  element.hidden = !visible;
  element.style.display = visible ? "" : "none";
}

function formatNow() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}

function createId() {
  return Math.random().toString(16).slice(2);
}

function autosizeTextarea(textarea) {
  textarea.style.height = "0px";
  textarea.style.height = `${Math.min(textarea.scrollHeight, 140)}px`;
}

const state = {
  activeAgentKey: null,
  activeChatId: null,
  view: "home",
  activeAgentDetailKey: null,
  chats: [],
};

function ensureInitialChats() {
  if (state.chats.length > 0) return;
  state.chats = [
    {
      id: createId(),
      title: "竣工测量面积计算 · 示例",
      createdAt: formatNow(),
      messages: [
        { role: "assistant", text: "你可以选择智能体，或直接输入需求开始对话。" },
      ],
    },
    {
      id: createId(),
      title: "一致性检查 · 示例",
      createdAt: formatNow(),
      messages: [
        { role: "assistant", text: "这里会展示历史对话（原型数据）。" },
      ],
    },
  ];
}

function renderHistory() {
  ensureInitialChats();
  const list = $("#historyList");
  const tpl = $("#tplHistoryItem");
  list.innerHTML = "";
  state.chats.slice(0, 8).forEach((chat) => {
    const node = tpl.content.firstElementChild.cloneNode(true);
    $(".history-item__title", node).textContent = chat.title;
    $(".history-item__meta", node).textContent = chat.createdAt;
    node.dataset.chatId = chat.id;
    node.addEventListener("click", () => openChat(chat.id));
    list.appendChild(node);
  });
}

function renderChips() {
  const holder = $("#chips");
  const tpl = $("#tplChip");
  holder.innerHTML = "";
  AGENTS.forEach((agent) => {
    const node = tpl.content.firstElementChild.cloneNode(true);
    $(".chip__name", node).textContent = agent.name;
    $(".chip__desc", node).textContent = agent.desc;
    node.addEventListener("click", () => selectAgent(agent.key, true));
    holder.appendChild(node);
  });
}

function renderQuickActions() {
  const holder = $("#quickActions");
  holder.innerHTML = "";
  AGENTS.forEach((agent) => {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "action-pill";
    btn.textContent = agent.name;
    btn.addEventListener("click", () => selectAgent(agent.key, true));
    holder.appendChild(btn);
  });
}

function renderChat() {
  const chat = state.chats.find((c) => c.id === state.activeChatId);
  const list = $("#chatList");
  const tpl = $("#tplMessage");

  list.innerHTML = "";
  if (!chat) return;

  chat.messages.forEach((m) => {
    const node = tpl.content.firstElementChild.cloneNode(true);
    node.classList.toggle("is-user", m.role === "user");
    $(".msg__bubble", node).textContent = m.text;
    list.appendChild(node);
  });

  list.scrollTop = list.scrollHeight;
}

function showHero() {
  setVisible($("#hero"), true);
  setVisible($("#chat"), false);
  setVisible($("#agentsPage"), false);
  setVisible($("#agentDetail"), false);
  setVisible($("#composerFooter"), true);
  $("#topbarCrumb").textContent = "新对话";
}

function showChat() {
  setVisible($("#hero"), false);
  setVisible($("#chat"), true);
  setVisible($("#agentsPage"), false);
  setVisible($("#agentDetail"), false);
  setVisible($("#composerFooter"), true);
  $("#topbarCrumb").textContent = "新对话";
}

function showAgents() {
  setVisible($("#hero"), false);
  setVisible($("#chat"), false);
  setVisible($("#agentsPage"), true);
  setVisible($("#agentDetail"), false);
  setVisible($("#composerFooter"), false);
  $("#topbarCrumb").textContent = "智能体";
}

function showAgentDetail() {
  setVisible($("#hero"), false);
  setVisible($("#chat"), false);
  setVisible($("#agentsPage"), false);
  setVisible($("#agentDetail"), true);
  setVisible($("#composerFooter"), false);
}

function selectAgent(agentKey, focusInput = false) {
  state.activeAgentKey = agentKey;
  state.activeAgentDetailKey = agentKey;
  const agent = AGENTS.find((a) => a.key === agentKey);
  const input = $("#composerInput");
  input.placeholder = agent ? agent.placeholder : "发送消息，或选择一个智能体开始";
  $$(".action-pill").forEach((el) => {
    el.style.borderColor = el.textContent === agent?.name ? "rgba(59,130,246,0.4)" : "";
    el.style.background =
      el.textContent === agent?.name ? "rgba(59,130,246,0.08)" : "";
  });
  if (focusInput) input.focus();
}

function clearAgentSelection() {
  state.activeAgentKey = null;
  const input = $("#composerInput");
  input.placeholder = "发送消息，或选择一个智能体开始";
  $$(".action-pill").forEach((el) => {
    el.style.borderColor = "";
    el.style.background = "";
  });
}

function newChat() {
  const agent = AGENTS.find((a) => a.key === state.activeAgentKey);
  const title = agent ? `${agent.name} · 新对话` : "新对话";
  const chat = {
    id: createId(),
    title,
    createdAt: formatNow(),
    messages: [
      {
        role: "assistant",
        text: agent
          ? `已选择智能体「${agent.name}」。请提供你的数据或需求。`
          : "你可以直接输入需求，智能体会自动判断并调用不同技能（原型展示）。",
      },
    ],
  };
  state.chats.unshift(chat);
  state.activeChatId = chat.id;
  renderHistory();
  showChat();
  renderChat();
  updateSendButton();
}

function openChat(chatId) {
  state.activeChatId = chatId;
  showChat();
  renderChat();
  updateSendButton();
}

function updateSendButton() {
  const input = $("#composerInput");
  $("#sendBtn").disabled = input.value.trim().length === 0;
}

function sendMessage() {
  const input = $("#composerInput");
  const text = input.value.trim();
  if (!text) return;

  if (!state.activeChatId) {
    newChat();
  }

  const chat = state.chats.find((c) => c.id === state.activeChatId);
  if (!chat) return;

  chat.messages.push({ role: "user", text });

  const agent = AGENTS.find((a) => a.key === state.activeAgentKey);
  const agentHint = agent
    ? `（原型）已路由到智能体：${agent.name}\n`
    : "（原型）智能体自动判断：将根据对话选择并调用不同技能\n";
  chat.messages.push({
    role: "assistant",
    text: `${agentHint}已收到：${text}\n\n这里仅做界面演示，不会实际调用后端能力。`,
  });

  input.value = "";
  autosizeTextarea(input);
  updateSendButton();
  showChat();
  renderChat();
  renderHistory();
}

function bindNav() {
  $$(".nav__item").forEach((btn) => {
    btn.addEventListener("click", () => {
      $$(".nav__item").forEach((b) => b.classList.remove("is-active"));
      btn.classList.add("is-active");
      const view = btn.dataset.view;
      if (view === "home") {
        state.activeChatId = null;
        state.view = "home";
        showHero();
      } else if (view === "agents") {
        state.activeChatId = null;
        state.view = "agents";
        showAgents();
      } else if (view === "history") {
        ensureInitialChats();
        state.view = "history";
        openChat(state.chats[0]?.id ?? null);
      }
    });
  });
}

function bindComposer() {
  const input = $("#composerInput");
  input.addEventListener("input", () => {
    autosizeTextarea(input);
    updateSendButton();
  });

  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });

  $("#sendBtn").addEventListener("click", sendMessage);

  $("#attachBtn").addEventListener("click", () => {
    const agent = AGENTS.find((a) => a.key === state.activeAgentKey);
    const hint = agent ? `当前智能体：${agent.name}` : "未选择智能体";
    const current = $("#composerInput").value.trim();
    $("#composerInput").value = current ? current : hint;
    autosizeTextarea($("#composerInput"));
    updateSendButton();
    $("#composerInput").focus();
  });
}

function bindShortcuts() {
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
      e.preventDefault();
      newChat();
    }
  });
}

function renderAgentsGrid() {
  const grid = $("#agentsGrid");
  const tpl = $("#tplAgentCard");
  const q = $("#agentsSearchInput").value.trim().toLowerCase();
  grid.innerHTML = "";

  AGENTS.filter((a) => a.category === "work")
    .filter((a) => {
      if (!q) return true;
      return `${a.name} ${a.desc}`.toLowerCase().includes(q);
    })
    .forEach((agent) => {
      const node = tpl.content.firstElementChild.cloneNode(true);
      $(".agent-card__icon", node).textContent = agent.name.slice(0, 1);
      $(".agent-card__title", node).textContent = agent.name;
      $(".agent-card__desc", node).textContent = agent.desc;
      $(".agent-card__meta", node).textContent = "进入专业功能页（参数化）";
      node.addEventListener("click", () => openAgentDetail(agent.key));
      grid.appendChild(node);
    });
}

function renderAgentDetail(agent) {
  $("#agentDetailTitle").textContent = agent.name;
  $("#agentDetailSub").textContent = agent.desc;
  $("#agentDetailDesc").textContent = agent.detail;
  const tags = $("#agentDetailTags");
  tags.innerHTML = "";
  (agent.tags ?? []).forEach((t) => {
    const el = document.createElement("div");
    el.className = "tag";
    el.textContent = t;
    tags.appendChild(el);
  });
}

function openAgentDetail(agentKey) {
  const agent = AGENTS.find((a) => a.key === agentKey);
  if (!agent) return;
  state.activeAgentDetailKey = agentKey;
  renderAgentDetail(agent);
  $("#topbarCrumb").textContent = `智能体 / ${agent.name}`;
  showAgentDetail();
}

function bindAgentsPage() {
  $("#agentsSearchInput").addEventListener("input", renderAgentsGrid);

  $$(".tab").forEach((btn) => {
    btn.addEventListener("click", () => {
      $$(".tab").forEach((b) => b.classList.remove("is-active"));
      btn.classList.add("is-active");
      renderAgentsGrid();
    });
  });

  $("#createAgentBtn").addEventListener("click", () => {
    state.activeAgentDetailKey = "create_agent";
    renderAgentDetail({
      name: "创建智能体（原型）",
      desc: "此处仅展示入口，不实现创建流程",
      detail:
        "真实产品中，你可以定义工具/技能、输入输出参数、校验规则与成果模板等。这里不做实现。",
      tags: ["技能编排", "参数校验", "成果模板", "权限与版本", "发布与评测"],
    });
    $("#topbarCrumb").textContent = "智能体 / 创建智能体";
    showAgentDetail();
  });
}

function bindAgentDetail() {
  const goAgents = () => {
    state.activeAgentDetailKey = null;
    $$(".nav__item").forEach((b) => b.classList.remove("is-active"));
    const agentsBtn = $(`.nav__item[data-view="agents"]`);
    agentsBtn?.classList.add("is-active");
    state.view = "agents";
    showAgents();
  };

  const goHome = () => {
    state.activeAgentDetailKey = null;
    $$(".nav__item").forEach((b) => b.classList.remove("is-active"));
    const homeBtn = $(`.nav__item[data-view="home"]`);
    homeBtn?.classList.add("is-active");
    state.view = "home";
    state.activeChatId = null;
    clearAgentSelection();
    showHero();
    $("#composerInput").focus();
  };

  $("#agentBackBtn").addEventListener("click", goAgents);
  $("#agentDetailToListBtn").addEventListener("click", goAgents);
  $("#agentDetailToHomeBtn").addEventListener("click", goHome);
}

function init() {
  renderChips();
  renderQuickActions();
  renderHistory();
  bindNav();
  bindComposer();
  bindShortcuts();
  bindAgentsPage();
  bindAgentDetail();
  renderAgentsGrid();
  showHero();
  updateSendButton();

  $("#newChatBtn").addEventListener("click", newChat);
}

init();
