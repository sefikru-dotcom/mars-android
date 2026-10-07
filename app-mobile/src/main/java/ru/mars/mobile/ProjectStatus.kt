package ru.mars.mobile

data class ProjectStatus(
    val name: String,
    val url: String,
    val description: String,
    val status: String = "Активен",
)

object MarsProjects {
    val all: List<ProjectStatus> = listOf(
        ProjectStatus("Квартал", "https://kvartalpro.ru", "Leaflet/Node.js гео-игра"),
        ProjectStatus("Альпен", "https://alpenhall.ru", "Караоке-баттл"),
        ProjectStatus("Квиз-Дуэль", "", "Игра-викторина"),
        ProjectStatus("Хост Aeza", "", "Linux, Caddy, AI-прокси, службы", "OK"),
    )
}
