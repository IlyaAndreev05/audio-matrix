import GLib from 'gi://GLib';

export const TEXT = {
    ru: {
        connecting: 'Подключение к движку…', unavailable: 'Движок Audio Matrix недоступен',
        disconnected: 'Не подключено',
        tabVolumeUnavailable: 'Независимая громкость вкладки недоступна',
        applicationVolumeUnavailable: 'Громкостью приложения нельзя управлять отдельно',
        outputVolumeUnavailable: 'Громкость выхода недоступна',
        outputUnavailable: 'Устройство вывода недоступно',
        outputs: 'УСТРОЙСТВА ВЫВОДА', applications: 'ПРИЛОЖЕНИЯ', open: 'Эквалайзер и маршруты',
        volume: 'Громкость', mute: 'Выключить звук', unmute: 'Включить звук', streams: 'Потоки',
        profile: 'Профиль', route: 'Маршрут',
        applicationUnavailable: 'Приложение недоступно',
        controls: 'ЗАКРЕПЛЁННЫЕ НАСТРОЙКИ', enabled: 'Включено', disabled: 'Выключено',
        matrix: 'Матрица', arrows: 'Стрелки', input: 'Вход ↓', activeLinks: 'Активные связи', changeLink: 'Связи',
        percent: 'Уровень громкости', commandError: 'Команда не выполнена',
        onOff: 'Вкл. / Выкл.', events: 'События',
        vibroOverall: 'Общая сила', vibroMixer: 'Микшер вибродинамика', vibroLimiter: 'Защита пиков',
        vibroOutput: 'Устройство вибродинамика', mainMixer: 'Основной микшер', allApplications: 'Все приложения, кроме событий',
        additionalInput: 'Дополнительный вход', details: 'Подробности', assignDevice: 'Назначьте устройство',
        deviceStates: {
            ONLINE: 'Подключено', UNBOUND: 'Не привязано', OFFLINE: 'Недоступно',
            AMBIGUOUS: 'Неоднозначная привязка', CONFLICT: 'Конфликт устройства', IGNORED: 'Игнорируется',
        },
    },
    en: {
        connecting: 'Connecting to engine…', unavailable: 'Audio Matrix engine unavailable',
        disconnected: 'Disconnected',
        tabVolumeUnavailable: 'Independent tab volume is unavailable',
        applicationVolumeUnavailable: 'Independent application volume is unavailable',
        outputVolumeUnavailable: 'Output volume is unavailable',
        outputUnavailable: 'Output device unavailable',
        outputs: 'OUTPUT DEVICES', applications: 'APPLICATIONS', open: 'EQ and routes',
        volume: 'Volume', mute: 'Mute', unmute: 'Unmute', streams: 'Streams',
        profile: 'Profile', route: 'Route',
        applicationUnavailable: 'Application unavailable',
        controls: 'PINNED CONTROLS', enabled: 'Enabled', disabled: 'Disabled',
        matrix: 'Matrix', arrows: 'Arrows', input: 'Input ↓', activeLinks: 'Active connections', changeLink: 'Connections',
        percent: 'Volume level', commandError: 'Command failed',
        onOff: 'On / Off', events: 'Events',
        vibroOverall: 'Vibration strength', vibroMixer: 'Vibration mixer', vibroLimiter: 'Peak protection',
        vibroOutput: 'Vibration device', mainMixer: 'Main mixer', allApplications: 'All apps except events',
        additionalInput: 'Additional input', details: 'Details', assignDevice: 'Assign a device',
        deviceStates: {
            ONLINE: 'Connected', UNBOUND: 'Not bound', OFFLINE: 'Offline',
            AMBIGUOUS: 'Ambiguous binding', CONFLICT: 'Device conflict', IGNORED: 'Ignored',
        },
    },
};

export function systemLanguage() {
    return GLib.get_language_names().some(name => name.startsWith('ru')) ? 'ru' : 'en';
}
