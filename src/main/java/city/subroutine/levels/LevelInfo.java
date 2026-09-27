package city.subroutine.levels;

import java.util.List;
import java.util.Objects;

/**
 * Презентационная часть уровня для клиента: сюжет, требования, контракт.
 *
 * @param chapter      глава кампании
 * @param order        порядок в кампании (1..N)
 * @param difficulty   сложность 1..5
 * @param district     район города (визуальная тема в клиенте): power, water, warehouse, telemetry, traffic, energy
 * @param summary      одна строка для списка уровней
 * @param brief        сюжетное описание задачи
 * @param requirements требования, которые проверяют тесты
 * @param goals        чему учит уровень
 * @param contractText исходный текст контракта (сигнатура или интерфейсы API) — показывается в IDE
 * @param starterCode  стартовый текст редактора: только пакет и комментарий, класс игрок пишет сам
 */
public record LevelInfo(
        String chapter,
        int order,
        int difficulty,
        String district,
        String summary,
        String brief,
        List<String> requirements,
        List<String> goals,
        String contractText,
        String starterCode) {

    public LevelInfo {
        Objects.requireNonNull(chapter, "chapter");
        if (difficulty < 1 || difficulty > 5) {
            throw new IllegalArgumentException("difficulty вне [1, 5]");
        }
        Objects.requireNonNull(district, "district");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(brief, "brief");
        requirements = List.copyOf(requirements);
        goals = List.copyOf(goals);
        Objects.requireNonNull(contractText, "contractText");
        Objects.requireNonNull(starterCode, "starterCode");
    }
}
