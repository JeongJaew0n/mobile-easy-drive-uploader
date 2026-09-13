package com.jjw.easygallery.feature.autotag

import com.jjw.easygallery.R

/**
 * ML Kit 기본 모델이 돌려주는 영어 라벨을 한국어로 보여준다(`docs/AUTO_TAGGING.md` §6).
 * 400개를 다 번역하지 않는다 — 사진에 자주 붙는 것부터 채우고, 표에 없으면 **영어 원문 그대로** 둔다.
 * 그래야 모델이 업데이트돼 새 라벨이 나와도 화면이 비지 않는다.
 */
internal object AutoTagLabels {

    fun displayNameRes(label: String): Int? = KOREAN[label]

    private val KOREAN: Map<String, Int> = mapOf(
        "Food" to R.string.auto_tag_label_food,
        "Dessert" to R.string.auto_tag_label_dessert,
        "Drink" to R.string.auto_tag_label_drink,
        "Plant" to R.string.auto_tag_label_plant,
        "Flower" to R.string.auto_tag_label_flower,
        "Tree" to R.string.auto_tag_label_tree,
        "Sky" to R.string.auto_tag_label_sky,
        "Cloud" to R.string.auto_tag_label_cloud,
        "Sunset" to R.string.auto_tag_label_sunset,
        "Beach" to R.string.auto_tag_label_beach,
        "Mountain" to R.string.auto_tag_label_mountain,
        "Snow" to R.string.auto_tag_label_snow,
        "Water" to R.string.auto_tag_label_water,
        "Building" to R.string.auto_tag_label_building,
        "Bridge" to R.string.auto_tag_label_bridge,
        "Street" to R.string.auto_tag_label_street,
        "Vehicle" to R.string.auto_tag_label_vehicle,
        "Car" to R.string.auto_tag_label_car,
        "Bicycle" to R.string.auto_tag_label_bicycle,
        "Person" to R.string.auto_tag_label_person,
        "Selfie" to R.string.auto_tag_label_selfie,
        "Crowd" to R.string.auto_tag_label_crowd,
        "Dog" to R.string.auto_tag_label_dog,
        "Cat" to R.string.auto_tag_label_cat,
        "Bird" to R.string.auto_tag_label_bird,
        "Pet" to R.string.auto_tag_label_pet,
        "Text" to R.string.auto_tag_label_text,
        "Document" to R.string.auto_tag_label_document,
        "Screenshot" to R.string.auto_tag_label_screenshot,
        "Poster" to R.string.auto_tag_label_poster,
        "Book" to R.string.auto_tag_label_book,
        "Furniture" to R.string.auto_tag_label_furniture,
        "Room" to R.string.auto_tag_label_room,
        "Table" to R.string.auto_tag_label_table,
        "Art" to R.string.auto_tag_label_art,
        "Night" to R.string.auto_tag_label_night,
        "Concert" to R.string.auto_tag_label_concert,
        "Sports" to R.string.auto_tag_label_sports,
        "Toy" to R.string.auto_tag_label_toy,
        "Fireworks" to R.string.auto_tag_label_fireworks,
    )
}
