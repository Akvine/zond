package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;

/**
 * Имена образов контейнеров: в Dockerfile, docker-compose, манифестах Kubernetes и файлах CI
 */
@UtilityClass
public class Images {
    private static final String SCRATCH = "scratch";
    private static final String LATEST = "latest";

    /**
     * @return true, если у образа нет тега либо тег - latest. Образ из переменной (${BASE_IMAGE}) проверить
     * нельзя; образ с дайджестом (@sha256:...) закреплен намертво
     */
    public boolean isUnpinned(String image) {
        if (image.isBlank() || image.equalsIgnoreCase(SCRATCH) || image.contains("$") || image.contains("@")
                || image.contains("{{")) {
            return false;
        }
        // Тег стоит после двоеточия в последней части имени: registry:5000/app - это порт, а не тег
        String lastPart = image.substring(image.lastIndexOf('/') + 1);
        int colon = lastPart.lastIndexOf(':');
        return colon < 0 || LATEST.equals(lastPart.substring(colon + 1));
    }
}
