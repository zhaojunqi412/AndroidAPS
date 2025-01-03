/**
 * GraphView
 * Copyright (C) 2014  Jonas Gehring
 *
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License,
 * with the "Linking Exception", which can be found at the license.txt
 * file in this program.
 *
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *
 * You should have received a copy of the GNU General Public License
 * with the "Linking Exception" along with this program; if not,
 * write to the author Jonas Gehring <g.jjoe64></g.jjoe64>@gmail.com>.
 */
package app.aaps.core.graph.data

import app.aaps.core.interfaces.graph.SeriesData
import com.jjoe64.graphview.series.BarGraphSeries
import com.jjoe64.graphview.series.DataPointInterface

class BarGraphSeries<E : DataPointInterface?> : BarGraphSeries<E>, SeriesData {
    constructor() : super()
    constructor(data: Array<E>) : super(data)
}
