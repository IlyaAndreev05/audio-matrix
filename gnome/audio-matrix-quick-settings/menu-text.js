import GLib from 'gi://GLib';

export const TEXT = {
    ru: {
        title: 'Audio Matrix', connecting: 'Подключение к движку…', unavailable: 'Движок Audio Matrix недоступен',
        outputs: 'УСТРОЙСТВА ВЫВОДА', applications: 'ПРИЛОЖЕНИЯ', open: 'Эквалайзер и маршруты — открыть редактор',
        volume: 'Громкость', mute: 'Выключить звук', unmute: 'Включить звук', streams: 'Потоки',
        profile: 'Профиль', route: 'Маршрут', noApplications: 'Нет активных приложений',
        controls: 'ЗАКРЕПЛЁННЫЕ НАСТРОЙКИ', enabled: 'Включено', disabled: 'Выключено',
        percent: 'Уровень громкости', commandError: 'Команда не выполнена',
    },
    en: {
        title: 'Audio Matrix', connecting: 'Connecting to engine…', unavailable: 'Audio Matrix engine unavailable',
        outputs: 'OUTPUT DEVICES', applications: 'APPLICATIONS', open: 'EQ and routes — open editor',
        volume: 'Volume', mute: 'Mute', unmute: 'Unmute', streams: 'Streams',
        profile: 'Profile', route: 'Route', noApplications: 'No active applications',
        controls: 'PINNED CONTROLS', enabled: 'Enabled', disabled: 'Disabled',
        percent: 'Volume level', commandError: 'Command failed',
    },
};

export function systemLanguage() {
    return GLib.get_language_names().some(name => name.startsWith('ru')) ? 'ru' : 'en';
}
