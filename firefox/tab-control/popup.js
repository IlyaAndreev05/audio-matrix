const list = document.querySelector('#tabs');
const status = document.querySelector('#status');

async function refresh() {
  try {
    const tabs = await browser.tabs.query({});
    list.replaceChildren();
    for (const tab of tabs) {
      const row = document.createElement('li');
      const title = document.createElement('span');
      title.className = 'title';
      title.textContent = tab.title || tab.url || 'Без названия';
      title.title = title.textContent;
      const focus = document.createElement('button');
      focus.type = 'button';
      focus.textContent = tab.active ? 'Активна' : 'Открыть';
      focus.disabled = Boolean(tab.active);
      focus.setAttribute('aria-label', tab.active ? 'Текущая вкладка' : 'Открыть ' + title.textContent);
      focus.addEventListener('click', async () => {
        await browser.tabs.update(tab.id, {active: true});
        window.close();
      });
      const muted = Boolean(tab.mutedInfo?.muted);
      const mute = document.createElement('button');
      mute.type = 'button';
      mute.textContent = muted ? 'Включить звук' : 'Выключить звук';
      mute.setAttribute('aria-pressed', String(muted));
      mute.setAttribute('aria-label', (muted ? 'Включить звук: ' : 'Выключить звук: ') + title.textContent);
      mute.addEventListener('click', async () => {
        try {
          await browser.tabs.update(tab.id, {muted: !muted});
          await refresh();
        } catch (error) {
          status.textContent = error.message || 'Не удалось изменить состояние звука вкладки.';
        }
      });
      row.append(title, focus, mute);
      list.append(row);
    }
    status.textContent = 'Вкладок: ' + tabs.length + ' · выключение звука без независимой PCM-громкости';
  } catch (error) {
    status.textContent = error.message || 'Не удалось прочитать список вкладок.';
  }
}

refresh();
