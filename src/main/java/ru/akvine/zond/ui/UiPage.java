package ru.akvine.zond.ui;

import lombok.experimental.UtilityClass;

/**
 * Страница Zond в браузере: выбор папки и настроек, запуск сканирования, ход проверки, отчет и список правил.
 * Страница самодостаточна - стили и скрипт внутри, с сервером она общается запросами к /api
 */
@UtilityClass
class UiPage {
    private static final String TOKEN_PLACE = "%TOKEN%";

    private static final String STYLE = """
            :root {
              --bg: #f6f7f9; --panel: #ffffff; --text: #1c2330; --muted: #5f6b7c; --border: #d9dee6;
              --accent: #1f5e9c; --accent-text: #ffffff; --error: #b42318;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #14171c; --panel: #1d2128; --text: #e6e9ee; --muted: #9aa4b2; --border: #313743;
                --accent: #7aa7ff; --accent-text: #14171c; --error: #ff8a7a;
              }
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--text);
                   font: 14px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
            header { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 10px 20px;
                     background: var(--panel); border-bottom: 1px solid var(--border); position: sticky; top: 0;
                     z-index: 2; }
            .brand { font-size: 18px; font-weight: 700; }
            .brand span { font-size: 13px; font-weight: 400; color: var(--muted); margin-left: 6px; }
            nav { display: flex; gap: 6px; flex: 1; }
            button { font: inherit; cursor: pointer; border-radius: 6px; border: 1px solid var(--border);
                     background: var(--panel); color: var(--text); padding: 6px 14px; }
            button:disabled { opacity: .55; cursor: default; }
            .tab[aria-pressed="true"] { background: var(--text); border-color: var(--text); color: var(--bg); }
            .primary { background: var(--accent); border-color: var(--accent); color: var(--accent-text); font-weight: 600; }
            main { max-width: 1280px; margin: 0 auto; padding: 16px 20px 32px; }
            .panel { background: var(--panel); border: 1px solid var(--border); border-radius: 8px; padding: 16px;
                     margin-bottom: 16px; }
            .field { display: flex; flex-direction: column; gap: 4px; color: var(--muted); font-size: 13px; }
            .row { display: flex; gap: 8px; align-items: center; }
            input[type="text"], input[type="search"], input[type="number"], select {
              font: inherit; color: var(--text); background: var(--bg); border: 1px solid var(--border);
              border-radius: 6px; padding: 7px 10px; width: 100%; min-width: 0; }
            .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 12px 16px;
                    margin-top: 12px; align-items: end; }
            .check { display: flex; align-items: center; gap: 8px; padding-bottom: 7px; }
            .actions { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
            .message { color: var(--muted); }
            .message.error { color: var(--error); }
            .browser { border: 1px solid var(--border); border-radius: 6px; margin-top: 8px; }
            .browser-head { display: flex; gap: 8px; align-items: center; padding: 8px 10px;
                            border-bottom: 1px solid var(--border); }
            .browser-head span { flex: 1; min-width: 0; overflow-wrap: anywhere; font-weight: 600; }
            .dirs { max-height: 260px; overflow-y: auto; padding: 6px; display: flex; flex-wrap: wrap; gap: 6px; }
            .dirs button { padding: 4px 10px; }
            .bar { height: 10px; border-radius: 999px; background: var(--bg); border: 1px solid var(--border);
                   overflow: hidden; }
            #barFill { height: 100%; width: 0; background: var(--accent); transition: width .3s; }
            .muted { color: var(--muted); }
            #ruleCount { white-space: nowrap; }
            #result { scroll-margin-top: 64px; }
            #progressText { margin-top: 8px; }
            .result-head { display: flex; flex-wrap: wrap; gap: 8px 24px; align-items: baseline;
                           margin-bottom: 12px; }
            #summary { flex: 1; min-width: 260px; }
            .exports a { color: var(--accent); margin-left: 8px; cursor: pointer; }
            iframe { width: 100%; height: calc(100vh - 150px); min-height: 480px; border: 1px solid var(--border);
                     border-radius: 6px; background: var(--bg); }
            .rule { display: grid; grid-template-columns: 76px minmax(0, 1fr) auto; gap: 4px 12px; padding: 8px 4px;
                    border-bottom: 1px solid var(--border); }
            .rule:last-child { border-bottom: 0; }
            .rule .code { color: var(--muted); font-variant-numeric: tabular-nums; }
            .rule .name { font-weight: 600; overflow-wrap: anywhere; }
            .rule .about { color: var(--muted); font-size: 13px; }
            .rule .buttons { display: flex; gap: 6px; align-items: start; }
            .rule .buttons button { padding: 2px 10px; font-size: 13px; }
            .rule.off .name { text-decoration: line-through; }
            .stopped { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center;
                       background: var(--bg); font-size: 18px; z-index: 3; }
            [hidden] { display: none !important; }
            """;

    private static final String BODY = """
            <header>
            <div class="brand">Zond<span id="version"></span></div>
            <nav>
            <button type="button" class="tab" id="tabScan" aria-pressed="true">Сканирование</button>
            <button type="button" class="tab" id="tabRules" aria-pressed="false">Правила</button>
            </nav>
            <button type="button" id="stop">Завершить</button>
            </header>
            <main>
            <section id="scanView">
            <div class="panel">
            <label class="field" for="path">Что проверять</label>
            <div class="row">
            <input id="path" type="text" placeholder="Папка проекта или .java-файл">
            <button type="button" id="browse">Обзор</button>
            </div>
            <div id="browser" class="browser" hidden>
            <div class="browser-head"><span id="browserPath"></span>
            <button type="button" id="choose" class="primary">Выбрать эту папку</button>
            <button type="button" id="closeBrowser">Закрыть</button></div>
            <div id="dirs" class="dirs"></div>
            </div>
            <div class="grid">
            <label class="field">Минимальный уровень<select id="minLevel"></select></label>
            <label class="field">Минимальная уверенность<select id="minConfidence"></select></label>
            <label class="field">Потоки (0 — по числу ядер)<input id="threads" type="number" min="0" max="128"></label>
            <label class="check"><input id="skipTests" type="checkbox">Пропускать каталоги test</label>
            <label class="check"><input id="changedOnly" type="checkbox">Только изменённые файлы</label>
            </div>
            <div class="grid">
            <label class="field">Только эти правила (пусто — все)
            <input id="onlyRules" type="text" placeholder="jr:510, jr:511"></label>
            <label class="field">Отключить правила
            <input id="disabledRules" type="text" placeholder="jr:40, MagicNumberRule"></label>
            <label class="field">Не проверять пути
            <input id="exclude" type="text" placeholder="generated, **/dto/**, *Mapper.java"></label>
            <label class="field">С чем сравнивать в git (пусто — с HEAD)
            <input id="changedSince" type="text" placeholder="main, origin/main, v1.2.0"></label>
            </div>
            <div class="actions">
            <button type="button" id="scan" class="primary">Сканировать</button>
            <span id="message" class="message"></span>
            </div>
            </div>
            <div id="progress" class="panel" hidden>
            <div class="bar"><div id="barFill"></div></div>
            <div id="progressText" class="muted"></div>
            </div>
            <div id="result" class="panel" hidden>
            <div class="result-head">
            <div id="summary"></div>
            <div class="exports">Скачать отчёт:<a data-format="html">HTML</a><a data-format="md">Markdown</a>
            <a data-format="xlsx">Excel</a><a data-format="sarif">SARIF</a><a data-format="txt">текст</a></div>
            </div>
            <iframe id="report" title="Отчёт о сканировании"></iframe>
            </div>
            </section>
            <section id="rulesView" hidden>
            <div class="panel">
            <div class="row">
            <input id="ruleSearch" type="search" placeholder="Поиск: код, имя, тип, описание">
            <span id="ruleCount" class="muted"></span>
            </div>
            <div id="rules"></div>
            </div>
            </section>
            </main>
            <div id="stopped" class="stopped" hidden>Zond остановлен. Вкладку можно закрыть.</div>
            """;

    private static final String SCRIPT = """
            const TOKEN = '%TOKEN%';
            const POLL_MS = 700;
            const byId = id => document.getElementById(id);
            const headers = { 'X-Zond-Token': TOKEN };
            let rules = [];
            let browsed = '';
            let stopArmed = false;
            let justScanned = false;

            async function request(path, options) {
              const response = await fetch(path, options);
              let data = {};
              try { data = await response.json(); } catch (ignored) { data = {}; }
              if (!response.ok) { throw new Error(data.error || ('Ошибка ' + response.status)); }
              return data;
            }
            const get = path => request(path, { headers });
            const post = (path, values) => request(path, {
              method: 'POST',
              headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, headers),
              body: new URLSearchParams(values || {}).toString()
            });

            function say(text, isError) {
              const message = byId('message');
              message.textContent = text;
              message.className = isError ? 'message error' : 'message';
            }
            function fill(select, values, chosen) {
              select.textContent = '';
              for (const value of values) {
                const option = document.createElement('option');
                option.value = value.value;
                option.textContent = value.title;
                option.selected = value.value === chosen;
                select.appendChild(option);
              }
            }
            function setBusy(busy) {
              byId('scan').disabled = busy;
              byId('scan').textContent = busy ? 'Идёт сканирование' : 'Сканировать';
              byId('progress').hidden = !busy;
            }

            async function loadState() {
              const state = await get('/api/state');
              byId('version').textContent = state.version;
              byId('path').value = state.path;
              fill(byId('minLevel'), state.levels, state.settings.minLevel);
              fill(byId('minConfidence'), state.confidences, state.settings.minConfidence);
              byId('threads').value = state.settings.threads;
              byId('skipTests').checked = state.settings.skipTests;
              byId('changedOnly').checked = state.settings.changedOnly;
              byId('onlyRules').value = state.settings.onlyRules;
              byId('disabledRules').value = state.settings.disabledRules;
              byId('exclude').value = state.settings.exclude;
              byId('changedSince').value = state.settings.changedSince;
            }

            // Папки читает сервер: браузер не отдает странице настоящие пути на диске
            async function browse(path) {
              try {
                const data = await get('/api/dirs?path=' + encodeURIComponent(path || ''));
                browsed = data.path;
                byId('browserPath').textContent = data.path;
                const list = byId('dirs');
                list.textContent = '';
                const add = (title, target) => {
                  const button = document.createElement('button');
                  button.type = 'button';
                  button.textContent = title;
                  button.addEventListener('click', () => browse(target));
                  list.appendChild(button);
                };
                if (data.parent) { add('.. (выше)', data.parent); }
                for (const root of data.roots) { add(root, root); }
                for (const dir of data.dirs) { add(dir.name, dir.path); }
                byId('browser').hidden = false;
              } catch (error) {
                say(error.message, true);
              }
            }

            async function startScan() {
              say('', false);
              try {
                await post('/api/scan', {
                  path: byId('path').value,
                  minLevel: byId('minLevel').value,
                  minConfidence: byId('minConfidence').value,
                  threads: byId('threads').value,
                  skipTests: byId('skipTests').checked,
                  changedOnly: byId('changedOnly').checked,
                  onlyRules: byId('onlyRules').value,
                  disabledRules: byId('disabledRules').value,
                  exclude: byId('exclude').value,
                  changedSince: byId('changedSince').value
                });
                byId('result').hidden = true;
                justScanned = true;
                setBusy(true);
                show({ running: true, finished: 0, total: 0, rule: '' });
                setTimeout(poll, POLL_MS);
              } catch (error) {
                say(error.message, true);
              }
            }
            async function poll() {
              try {
                const status = await get('/api/status');
                show(status);
                if (status.running) { setTimeout(poll, POLL_MS); }
              } catch (error) {
                setBusy(false);
                say('Связь со страницей потеряна: ' + error.message, true);
              }
            }
            function show(status) {
              if (status.running) {
                setBusy(true);
                const share = status.total > 0 ? Math.round(100 * status.finished / status.total) : 0;
                byId('barFill').style.width = share + '%';
                byId('progressText').textContent = status.total > 0
                    ? 'Правило ' + Math.min(status.finished + 1, status.total) + ' из ' + status.total + ': ' + status.rule
                    : 'Загрузка и разбор кода';
                return;
              }
              setBusy(false);
              if (status.error) {
                say('Ошибка: ' + status.error, true);
              } else if (status.result) {
                const result = status.result;
                byId('summary').textContent = 'Найдено проблем: ' + result.violations + ' · файлов проверено: '
                    + result.files + ' · правил: ' + result.rules + ' · время: ' + result.time + ' · ' + result.path;
                byId('report').src = '/report?token=' + TOKEN + '&scan=' + status.scan;
                byId('result').hidden = false;
                // Отчет только что готов - показываем его; при простом открытии страницы она остается наверху
                if (justScanned) {
                  justScanned = false;
                  byId('result').scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
              }
            }

            // Список запрашивается при каждом открытии вкладки: отключенные правила в нем зависят от настроек
            async function loadRules() {
              rules = await get('/api/rules');
              renderRules();
            }
            function renderRules() {
              const query = byId('ruleSearch').value.trim().toLowerCase();
              const list = byId('rules');
              list.textContent = '';
              let count = 0;
              for (const rule of rules) {
                const text = (rule.code + ' ' + rule.name + ' ' + rule.type + ' ' + rule.level + ' '
                    + rule.description).toLowerCase();
                if (query && !text.includes(query)) { continue; }
                count++;
                const row = document.createElement('div');
                row.className = rule.active ? 'rule' : 'rule off';
                const part = (className, value) => {
                  const element = document.createElement('div');
                  element.className = className;
                  element.textContent = value;
                  return element;
                };
                const body = document.createElement('div');
                body.appendChild(part('name', rule.name));
                body.appendChild(part('about', rule.type + ' · ' + rule.level + (rule.active ? '' : ' · выключено')));
                body.appendChild(part('about', rule.description));
                const buttons = document.createElement('div');
                buttons.className = 'buttons';
                const action = (title, field) => {
                  const button = document.createElement('button');
                  button.type = 'button';
                  button.textContent = title;
                  button.addEventListener('click', () => addRule(field, rule.code));
                  buttons.appendChild(button);
                };
                action('Только это', 'onlyRules');
                action('Отключить', 'disabledRules');
                row.appendChild(part('code', rule.code));
                row.appendChild(body);
                row.appendChild(buttons);
                list.appendChild(row);
              }
              byId('ruleCount').textContent = 'Показано: ' + count + ' из ' + rules.length;
            }
            // Код правила дописывается в поле на вкладке сканирования
            function addRule(field, code) {
              const input = byId(field);
              const codes = input.value.split(',').map(value => value.trim()).filter(value => value);
              if (!codes.includes(code)) { codes.push(code); }
              input.value = codes.join(', ');
              openTab('scan');
              say('Правило ' + code + ' добавлено в поле «'
                  + (field === 'onlyRules' ? 'Только эти правила' : 'Отключить правила') + '»', false);
            }

            function openTab(name) {
              byId('scanView').hidden = name !== 'scan';
              byId('rulesView').hidden = name !== 'rules';
              byId('tabScan').setAttribute('aria-pressed', String(name === 'scan'));
              byId('tabRules').setAttribute('aria-pressed', String(name === 'rules'));
              if (name === 'rules') { loadRules().catch(error => say(error.message, true)); }
            }
            // Завершение со второго нажатия: случайный щелчок не должен останавливать приложение
            async function stop() {
              const button = byId('stop');
              if (!stopArmed) {
                stopArmed = true;
                button.textContent = 'Нажмите ещё раз, чтобы завершить';
                setTimeout(() => { stopArmed = false; button.textContent = 'Завершить'; }, 4000);
                return;
              }
              try { await post('/api/stop'); } catch (ignored) { /* сервер мог остановиться раньше ответа */ }
              byId('stopped').hidden = false;
            }

            byId('tabScan').addEventListener('click', () => openTab('scan'));
            byId('tabRules').addEventListener('click', () => openTab('rules'));
            byId('browse').addEventListener('click', () => browse(byId('path').value));
            byId('choose').addEventListener('click', () => { byId('path').value = browsed; byId('browser').hidden = true; });
            byId('closeBrowser').addEventListener('click', () => { byId('browser').hidden = true; });
            byId('scan').addEventListener('click', startScan);
            byId('path').addEventListener('keydown', event => { if (event.key === 'Enter') { startScan(); } });
            byId('ruleSearch').addEventListener('input', renderRules);
            byId('stop').addEventListener('click', stop);
            for (const link of document.querySelectorAll('.exports a')) {
              link.href = '/api/export?format=' + link.dataset.format + '&token=' + TOKEN;
            }
            loadState().then(poll).catch(error => say(error.message, true));
            """;

    /**
     * @param token ключ сеанса из букв и цифр: с ним страница обращается к серверу
     */
    String render(String token) {
        return "<!doctype html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>Zond</title>\n"
                // Пустой значок: иначе браузер запросил бы /favicon.ico без ключа и получил отказ
                + "<link rel=\"icon\" href=\"data:,\">\n"
                + "<style>\n" + STYLE + "</style>\n</head>\n<body>\n" + BODY
                + "<script>\n" + SCRIPT.replace(TOKEN_PLACE, token) + "</script>\n</body>\n</html>\n";
    }
}
